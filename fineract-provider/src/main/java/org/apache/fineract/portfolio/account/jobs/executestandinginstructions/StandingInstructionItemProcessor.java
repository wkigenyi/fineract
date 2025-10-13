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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.data.StandingInstructionDuesData;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RequiredArgsConstructor
public class StandingInstructionItemProcessor implements ItemProcessor<StandingInstructionData, AccountTransferRequest> {

    private final StandingInstructionReadPlatformService standingInstructionReadPlatformService;
    private final ScheduledDateGenerator scheduledDateGenerator = new DefaultScheduledDateGenerator();
    private final JdbcTemplate jdbcTemplate;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final DatabaseSpecificSQLGenerator sqlGenerator;

    @Override
    public AccountTransferRequest process(StandingInstructionData data) throws Exception {
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
        BigDecimal transactionAmount = data.getAmount();
        if (data.getToAccountType().isLoanAccount()
                && (recurrenceType.isDuesRecurrence() || (isDueForTransfer && instructionType.isDuesAmoutTransfer()))) {
            StandingInstructionDuesData standingInstructionDuesData = standingInstructionReadPlatformService
                    .retriveLoanDuesData(data.getToAccount().getId());
            if (data.getInstructionType().isDuesAmoutTransfer()) {
                transactionAmount = standingInstructionDuesData.totalDueAmount();
            }
            if (recurrenceType.isDuesRecurrence()) {
                isDueForTransfer = isDueForTransfer(standingInstructionDuesData);
            }
        }

        if (isDueForTransfer && transactionAmount != null && transactionAmount.compareTo(BigDecimal.ZERO) > 0) {
            final SavingsAccount fromSavingsAccount = null;
            final boolean isRegularTransaction = true;
            final boolean isExceptionForBalanceCheck = false;
            AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, transactionAmount,
                    data.getFromAccountType(), data.getToAccountType(), data.getFromAccount().getId(), data.getToAccount().getId(),
                    data.getName() + " Standing instruction transfer ", null, null, null, null, data.toTransferType(), null, null,
                    data.getTransferType().getValue(), null, null, ExternalId.empty(), null, null, fromSavingsAccount,
                    isRegularTransaction, isExceptionForBalanceCheck);

            return new AccountTransferRequest(accountTransferDTO,data);
        }

        return null;

    }

    public boolean isDueForTransfer(StandingInstructionDuesData standingInstructionDuesData) {
        return standingInstructionDuesData.dueDate() != null
                && !standingInstructionDuesData.dueDate().isAfter(LocalDate.now(DateUtils.getDateTimeZoneOfTenant()));
    }


}
