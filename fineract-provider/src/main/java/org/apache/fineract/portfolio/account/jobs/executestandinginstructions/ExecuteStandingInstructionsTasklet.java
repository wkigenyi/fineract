/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.account.jobs.executestandinginstructions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformServiceUnavailableException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.data.StandingInstructionDuesData;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@RequiredArgsConstructor
public class ExecuteStandingInstructionsTasklet implements Tasklet {

    private final StandingInstructionReadPlatformService standingInstructionReadPlatformService;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final PlatformTransactionManager transactionManager;
    private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        Collection<StandingInstructionData> instructionData = standingInstructionReadPlatformService
                .retrieveAll(StandingInstructionStatus.ACTIVE.getValue());
        List<Throwable> errors = new ArrayList<>();
        List<StandingInstructionExecutionResult> results = new ArrayList<>();
        for (StandingInstructionData data : instructionData) {
            boolean isDueForTransfer = false;
            AccountTransferRecurrenceType recurrenceType = data.getRecurrenceType();
            StandingInstructionType instructionType = data.getInstructionType();
            LocalDate transactionDate = DateUtils.getBusinessLocalDate();
            if (recurrenceType.isPeriodicRecurrence()) {
                final ScheduledDateGenerator scheduledDateGenerator = new DefaultScheduledDateGenerator();
                PeriodFrequencyType frequencyType = data.getRecurrenceFrequency();
                LocalDate startDate = data.getValidFrom();
                if (frequencyType.isMonthly()) {
                    startDate = startDate.withDayOfMonth(data.getRecurrenceOnDay());
                    if (DateUtils.isBefore(startDate, data.getValidFrom())) {
                        startDate = startDate.plusMonths(1);
                    }
                } else if (frequencyType.isYearly()) {
                    startDate = startDate.withDayOfMonth(data.getRecurrenceOnDay()).withMonth(data.getRecurrenceOnMonth());
                    if (DateUtils.isBefore(startDate, data.getValidFrom())) {
                        startDate = startDate.plusYears(1);
                    }
                }
                isDueForTransfer = scheduledDateGenerator.isDateFallsInSchedule(frequencyType, data.getRecurrenceInterval(), startDate,
                        transactionDate);

            }
            BigDecimal transactionAmount = data.getAmount() == null ? BigDecimal.ZERO : data.getAmount();
            boolean isInactiveLoan = false;
            Integer currentLoanStatus = null;
            if (PortfolioAccountType.LOAN.equals(data.getToAccountType())) {
                StandingInstructionDuesData duesData = standingInstructionReadPlatformService
                        .retriveLoanDuesData(data.getToAccount().getId());
                currentLoanStatus = duesData.loanStatus();
                if (!duesData.isLoanActive()) {
                    isInactiveLoan = true;
                    if (recurrenceType.isDuesRecurrence() || (isDueForTransfer && instructionType.isDuesAmoutTransfer())) {
                        isDueForTransfer = false;
                    }
                } else if (recurrenceType.isDuesRecurrence() || (isDueForTransfer && instructionType.isDuesAmoutTransfer())) {
                    if (data.getInstructionType().isDuesAmoutTransfer()) {
                        transactionAmount = duesData.totalDueAmount();
                    }
                    if (recurrenceType.isDuesRecurrence()) {
                        isDueForTransfer = isDueForTransfer(duesData);
                    }
                }
            }

            if ((isDueForTransfer && transactionAmount != null && transactionAmount.compareTo(BigDecimal.ZERO) > 0) || isInactiveLoan) {
                final BigDecimal originalAmount = transactionAmount;
                boolean insufficientBalance = false;
                boolean partialPayment = false;
                if (!isInactiveLoan && data.getFromAccountType().isSavingsAccount()) {
                    final SavingsAccountData savingsAccountData = savingsAccountReadPlatformService.retrieveOne(data.getFromAccount().getId());
                    final BigDecimal availableBalance = savingsAccountData.getSummary().getAvailableBalance();
                    if (availableBalance == null || availableBalance.compareTo(BigDecimal.ZERO) <= 0) {
                        insufficientBalance = true;
                        transactionAmount = BigDecimal.ZERO;
                    } else if (transactionAmount.compareTo(availableBalance) > 0) {
                        partialPayment = true;
                        transactionAmount = availableBalance;
                    }
                }

                final BigDecimal finalAmount = transactionAmount;
                final StandingInstructionData finalData = data;
                final List<Throwable> finalErrors = errors;
                final boolean finalIsInactiveLoan = isInactiveLoan;
                final Long finalToLoanId = data.getToAccount().getId();
                final boolean finalInsufficientBalance = insufficientBalance;
                final boolean finalPartialPayment = partialPayment;
                final Integer finalLoanStatus = currentLoanStatus;

                TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
                transactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

                StandingInstructionExecutionResult result;
                try {
                    result = transactionTemplate.execute(status -> {
                        final SavingsAccount fromSavingsAccount = null;
                        final boolean isRegularTransaction = true;
                        final boolean isExceptionForBalanceCheck = false;
                        AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, finalAmount,
                                finalData.getFromAccountType(), finalData.getToAccountType(), finalData.getFromAccount().getId(),
                                finalData.getToAccount().getId(), finalData.getName() + " Standing instruction trasfer ", null, null, null,
                                null, finalData.toTransferType(), null, null, finalData.getTransferType().getValue(), null, null,
                                ExternalId.empty(), null, null, fromSavingsAccount, isRegularTransaction, isExceptionForBalanceCheck);

                        StandingInstructionExecutionResult res = executeTransfer(accountTransferDTO, finalData.getId(),
                                finalInsufficientBalance, finalPartialPayment, originalAmount, finalIsInactiveLoan, finalToLoanId, finalLoanStatus,
                                finalData.getFromAccount().getAccountNo(), finalData.getToAccount().getAccountNo());

                        if (res.isSuccess()) {
                            final String updateQuery = "UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?";
                            jdbcTemplate.update(updateQuery, transactionDate, finalData.getId());
                        } else {
                            status.setRollbackOnly();
                        }
                        return res;
                    });
                } catch (Exception e) {
                    log.error("Error executing standing instruction {}", finalData.getId(), e);
                    result = StandingInstructionExecutionResult.failure(finalData.getId(), finalAmount, "System error: " + e.getMessage(), List.of(e));
                }

                if (result != null) {
                    errors.addAll(result.getCaughtErrors());
                    // Skip immediate logging, we will aggregate at the end
                    contribution.incrementWriteCount(1);
                    results.add(result);
                }
            }
        }
        
        // Final Job-Level Log Aggregation
        StringBuilder summaryLog = new StringBuilder("Standing Instructions Job Execution Summary: ");
        int total = instructionData.size();
        int processed = results.size();
        for (StandingInstructionExecutionResult result : results) {
            String entry = "\n - Instruction [" + result.getInstructionId() + "]: " + result.getErrorLog();
            if (summaryLog.length() + entry.length() > 60000) {
                summaryLog.append("\n... [TRUNCATED]");
                break;
            }
            summaryLog.append(entry);
        }
        log.info("Processed {}/{} instructions. Details: {}", processed, total, summaryLog.toString());
        contribution.setExitStatus(new ExitStatus("COMPLETED", summaryLog.toString()));
        
        // Record results to individual history tables at the end (batched/sequential but outside loop)
        for (StandingInstructionExecutionResult result : results) {
            logHistoryIndependent(result.getInstructionId(), result);
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
        return RepeatStatus.FINISHED;
    }

    private StandingInstructionExecutionResult executeTransfer(final AccountTransferDTO accountTransferDTO, final Long instructionId,
                                                       final boolean isInsufficientBalance, final boolean isPartialPayment, final BigDecimal originalAmount, final boolean isInactiveLoan,
                                                       final Long toLoanId, final Integer currentLoanStatus, final String fromAccountNo, final String toAccountNo) {
        boolean transactionFailed = false;
        List<Throwable> caughtErrors = new ArrayList<>();
        StringBuilder errorLog = new StringBuilder();
        if (isInactiveLoan) {
            errorLog.append("Loan [ID: ").append(toLoanId).append(", No: ").append(toAccountNo).append("] has status ").append(currentLoanStatus).append(", it cannot receive payments. ");
        } else if (isInsufficientBalance) {
            errorLog.append("Skipped: Insufficient balance for Savings Account [ID: ").append(accountTransferDTO.getFromAccountId()).append(", No: ").append(fromAccountNo)
                    .append("] to Loan [ID: ").append(toLoanId).append(", No: ").append(toAccountNo).append("]. ");
        } else if (isPartialPayment) {
            errorLog.append("Partial settlement: Required ").append(originalAmount).append(", Paid ")
                    .append(accountTransferDTO.getTransactionAmount()).append(". ");
        }

        try {
            if (isInactiveLoan || isInsufficientBalance) {
                // No transfer needed
            } else if (accountTransferDTO.getTransactionAmount().compareTo(BigDecimal.ZERO) > 0) {
                accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
            } else {
                errorLog.append("Transfer skipped due to zero balance.");
            }
        } catch (final PlatformApiDataValidationException e) {
            transactionFailed = true;
            caughtErrors.add(new Exception("Validation exception while transfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Validation exception while trasfering funds ").append(e.getDefaultUserMessage());
        } catch (final InsufficientAccountBalanceException e) {
            // Business condition, log it but don't fail the job
            errorLog.append("InsufficientAccountBalance Exception (Service Level) ");
        } catch (final AbstractPlatformServiceUnavailableException e) {
            transactionFailed = true;
            caughtErrors.add(new Exception("Platform exception while trasfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Platform exception while trasfering funds ").append(e.getDefaultUserMessage());
        } catch (Exception e) {
            transactionFailed = true;
            caughtErrors.add(new Exception("Unhandled System Exception while trasfering funds for standing Instruction id" + instructionId
                    + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Exception while trasfering funds ").append(e.getMessage());
        }

        if (transactionFailed) {
            return StandingInstructionExecutionResult.failure(instructionId, accountTransferDTO.getTransactionAmount(), errorLog.toString(), caughtErrors);
        } else {
            return StandingInstructionExecutionResult.success(instructionId, accountTransferDTO.getTransactionAmount(), errorLog.toString());
        }
    }

    private void logHistoryIndependent(final Long instructionId, final StandingInstructionExecutionResult result) {
        StringBuilder updateQuery = new StringBuilder(
                "INSERT INTO m_account_transfer_standing_instructions_history (standing_instruction_id, " + sqlGenerator.escape("status")
                        + ", amount,execution_time, error_log) VALUES (");
        updateQuery.append(instructionId).append(",");
        if (result.isSuccess()) {
            updateQuery.append("'success'").append(",");
        } else {
            updateQuery.append("'failed'").append(",");
        }
        updateQuery.append(result.getAmount() != null ? result.getAmount().doubleValue() : 0);
        updateQuery.append(", now(),");
        updateQuery.append("'").append(result.getErrorLog()).append("')");

        try {
            TransactionTemplate logTransactionTemplate = new TransactionTemplate(transactionManager);
            logTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
            logTransactionTemplate.execute(status -> {
                jdbcTemplate.update(updateQuery.toString());
                return null;
            });
        } catch (Exception e) {
            log.error("Error logging standing instruction history for instruction {}", instructionId, e);
        }
    }

    private static final class StandingInstructionExecutionResult {

        private final boolean success;
        private final BigDecimal amount;
        private final String errorLog;
        private final List<Throwable> caughtErrors;
        private final Long instructionId;

        private StandingInstructionExecutionResult(Long instructionId, boolean success, BigDecimal amount, String errorLog, List<Throwable> caughtErrors) {
            this.instructionId = instructionId;
            this.success = success;
            this.amount = amount;
            this.errorLog = errorLog;
            this.caughtErrors = caughtErrors;
        }

        public static StandingInstructionExecutionResult success(Long instructionId, BigDecimal amount, String errorLog) {
            return new StandingInstructionExecutionResult(instructionId, true, amount, errorLog, new ArrayList<>());
        }

        public static StandingInstructionExecutionResult failure(Long instructionId, BigDecimal amount, String errorLog, List<Throwable> caughtErrors) {
            return new StandingInstructionExecutionResult(instructionId, false, amount, errorLog, caughtErrors);
        }

        public Long getInstructionId() {
            return instructionId;
        }

        public boolean isSuccess() {
            return success;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getErrorLog() {
            return errorLog;
        }

        public List<Throwable> getCaughtErrors() {
            return caughtErrors;
        }
    }

    public boolean isDueForTransfer(StandingInstructionDuesData standingInstructionDuesData) {
        return standingInstructionDuesData.dueDate() != null
                && !standingInstructionDuesData.dueDate().isAfter(LocalDate.now(DateUtils.getDateTimeZoneOfTenant()));
    }
}
