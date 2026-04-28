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
package org.apache.fineract.portfolio.shareaccounts.service;

import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormat;
import org.apache.fineract.infrastructure.accountnumberformat.domain.AccountNumberFormatRepositoryWrapper;
import org.apache.fineract.infrastructure.accountnumberformat.domain.EntityAccountType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.event.business.domain.share.ShareAccountApproveBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.share.ShareAccountCreateBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.service.AccountNumberGenerator;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.accounts.constants.ShareAccountApiConstants;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.apache.fineract.portfolio.shareaccounts.data.ShareAccountTransactionEnumData;
import org.apache.fineract.portfolio.shareaccounts.domain.ShareAccount;
import org.apache.fineract.portfolio.shareaccounts.domain.ShareAccountChargePaidBy;
import org.apache.fineract.portfolio.shareaccounts.domain.ShareAccountRepositoryWrapper;
import org.apache.fineract.portfolio.shareaccounts.domain.ShareAccountTransaction;
import org.apache.fineract.portfolio.shareaccounts.serialization.ShareAccountDataSerializer;
import org.apache.fineract.portfolio.shareproducts.domain.ShareProduct;
import org.apache.fineract.portfolio.shareproducts.domain.ShareProductRepositoryWrapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;

public class ShareAccountWritePlatformServiceJpaRepositoryImpl implements ShareAccountWritePlatformService {

    private final ShareAccountDataSerializer accountDataSerializer;

    private final ShareAccountRepositoryWrapper shareAccountRepository;

    private final ShareProductRepositoryWrapper shareProductRepository;

    private final AccountNumberGenerator accountNumberGenerator;

    private final AccountNumberFormatRepositoryWrapper accountNumberFormatRepository;

    private final JournalEntryWritePlatformService journalEntryWritePlatformService;

    private final NoteRepository noteRepository;

    private final BusinessEventNotifierService businessEventNotifierService;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    public ShareAccountWritePlatformServiceJpaRepositoryImpl(final ShareAccountDataSerializer accountDataSerializer,
            final ShareAccountRepositoryWrapper shareAccountRepository, final ShareProductRepositoryWrapper shareProductRepository,
            final AccountNumberGenerator accountNumberGenerator,
            final AccountNumberFormatRepositoryWrapper accountNumberFormatRepository,
            final JournalEntryWritePlatformService journalEntryWritePlatformService, final NoteRepository noteRepository,
            final BusinessEventNotifierService businessEventNotifierService,
            final AccountTransfersWritePlatformService accountTransfersWritePlatformService,
            final SavingsAccountWritePlatformService savingsAccountWritePlatformService,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository) {
        this.accountDataSerializer = accountDataSerializer;
        this.shareAccountRepository = shareAccountRepository;
        this.shareProductRepository = shareProductRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.accountNumberFormatRepository = accountNumberFormatRepository;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.noteRepository = noteRepository;
        this.businessEventNotifierService = businessEventNotifierService;
        this.accountTransfersWritePlatformService = accountTransfersWritePlatformService;
        this.savingsAccountWritePlatformService = savingsAccountWritePlatformService;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
    }

    @Override
    public CommandProcessingResult createShareAccount(JsonCommand jsonCommand) {
        try {
            ShareAccount account = this.accountDataSerializer.validateAndCreate(jsonCommand);
            this.shareAccountRepository.saveAndFlush(account);
            generateAccountNumber(account);

            // Do not hold funds, just verify balance
            for (ShareAccountTransaction transaction : account.getShareAccountTransactions()) {
                if (transaction.isUsingSavings()) {
                    validateSufficientFunds(account, transaction);
                }
            }

            journalEntryWritePlatformService.createJournalEntriesForShares(
                    populateJournalEntries(account, account.getPendingForApprovalSharePurchaseTransactions()));

            businessEventNotifierService.notifyPostBusinessEvent(new ShareAccountCreateBusinessEvent(account));

            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(account.getId()) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            Throwable throwable = ExceptionUtils.getRootCause(dve.getCause());
            handleDataIntegrityIssues(jsonCommand, throwable, dve);
            return CommandProcessingResult.empty();
        }
    }

    private void validateSufficientFunds(ShareAccount account, ShareAccountTransaction transaction) {
        BigDecimal requiredAmount = transaction.amount();
        if (transaction.chargeAmount() != null) {
            requiredAmount = requiredAmount.add(transaction.chargeAmount());
        }

        SavingsAccount savingsAccount = account.getSavingsAccount();
        BigDecimal availableBalance = savingsAccount.getWithdrawableBalance();

        if (availableBalance.compareTo(requiredAmount) < 0) {
            throw new InsufficientAccountBalanceException("share.purchase",
                    savingsAccount.getAccountBalance(), BigDecimal.ZERO, requiredAmount);
        }
    }

    private void generateAccountNumber(final ShareAccount account) {
        if (account.isAccountNumberRequiresAutoGeneration()) {
            final AccountNumberFormat accountNumberFormat = this.accountNumberFormatRepository.findByAccountType(EntityAccountType.SHARES);
            account.updateAccountNumber(this.accountNumberGenerator.generate(account, accountNumberFormat));
            this.shareAccountRepository.save(account);
        }
    }

    private Map<String, Object> populateJournalEntries(final ShareAccount account, final Set<ShareAccountTransaction> transactions) {
        final Map<String, Object> accountingBridgeData = new HashMap<>();
        Boolean cashBasedAccounting = account.getShareProduct().getAccountingType() == 2;
        accountingBridgeData.put("cashBasedAccountingEnabled", cashBasedAccounting);
        accountingBridgeData.put("accrualBasedAccountingEnabled", Boolean.FALSE);
        accountingBridgeData.put("shareAccountId", account.getId());
        accountingBridgeData.put("shareProductId", account.getShareProduct().getId());
        accountingBridgeData.put("officeId", account.getOfficeId());
        accountingBridgeData.put("currencyCode", account.getCurrency().getCode());
        final List<Map<String, Object>> newTransactionsMap = new ArrayList<>();
        accountingBridgeData.put("newTransactions", newTransactionsMap);

        for (ShareAccountTransaction transaction : transactions) {
            final Map<String, Object> transactionDto = new HashMap<>();
            transactionDto.put("officeId", account.getOfficeId());
            transactionDto.put("id", transaction.getId());
            transactionDto.put("date", transaction.getPurchasedDate());
            final Integer status = transaction.getTransactionStatus();
            final ShareAccountTransactionEnumData statusEnum = new ShareAccountTransactionEnumData(status.longValue(), null, null);
            final Integer type = transaction.getTransactionType();
            final ShareAccountTransactionEnumData typeEnum = new ShareAccountTransactionEnumData(type.longValue(), null, null);
            transactionDto.put("status", statusEnum);
            transactionDto.put("type", typeEnum);
            transactionDto.put("useSavings", transaction.isUsingSavings());
            if (transaction.isPurchaseRejectedTransaction() || transaction.isRedeemTransaction()) {
                BigDecimal amount = transaction.amount();
                if (transaction.chargeAmount() != null) {
                    amount = amount.add(transaction.chargeAmount());
                }
                transactionDto.put("amount", amount);
            } else {
                transactionDto.put("amount", transaction.amount());
            }

            transactionDto.put("chargeAmount", transaction.chargeAmount());
            transactionDto.put("paymentTypeId", null); // FIXME::make it cash
                                                       // payment
            if (transaction.getChargesPaidBy() != null && !transaction.getChargesPaidBy().isEmpty()) {
                final List<Map<String, Object>> chargesPaidData = new ArrayList<>();
                transactionDto.put("chargesPaid", chargesPaidData);
                Set<ShareAccountChargePaidBy> chargesPaidBySet = transaction.getChargesPaidBy();
                for (ShareAccountChargePaidBy chargesPaidBy : chargesPaidBySet) {
                    Map<String, Object> chargesPaidDto = new HashMap<>();
                    chargesPaidDto.put("chargeId", chargesPaidBy.getChargeId());
                    chargesPaidDto.put("sharesChargeId", chargesPaidBy.getShareChargeId());
                    chargesPaidDto.put("amount", chargesPaidBy.getAmount());
                    chargesPaidData.add(chargesPaidDto);
                }
            }
            newTransactionsMap.add(transactionDto);
        }
        return accountingBridgeData;
    }

    @SuppressWarnings("unchecked")
    @Override
    public CommandProcessingResult updateShareAccount(Long accountId, JsonCommand jsonCommand) {
        try {
            LocalDate transactionDate = DateUtils.getBusinessLocalDate();
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndUpdate(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.save(account);
            }
            // since we are reverting all journal entries we need to add journal
            // entries for application request
            if (changes.containsKey("reversalIds")) {
                ArrayList<Long> reversalIds = (ArrayList<Long>) changes.get("reversalIds");
                this.journalEntryWritePlatformService.revertShareAccountJournalEntries(reversalIds, transactionDate);
                journalEntryWritePlatformService.createJournalEntriesForShares(
                        populateJournalEntries(account, account.getPendingForApprovalSharePurchaseTransactions()));
                changes.remove("reversalIds");
            }
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            Throwable throwable = ExceptionUtils.getRootCause(dve.getCause());
            handleDataIntegrityIssues(jsonCommand, throwable, dve);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult applyAddtionalShares(final Long accountId, JsonCommand jsonCommand) {
        try {
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndApplyAddtionalShares(jsonCommand, account);
            ShareAccountTransaction transaction = null;
            if (!changes.isEmpty()) {
                this.shareAccountRepository.saveAndFlush(account);
                transaction = (ShareAccountTransaction) changes.get(ShareAccountApiConstants.additionalshares_paramname);
                transaction = account.getShareAccountTransaction(transaction);
                if (transaction != null) {
                    if (transaction.isUsingSavings()) {
                        validateSufficientFunds(account, transaction);
                        transaction = account.getShareAccountTransaction(transaction);
                    }
                    changes.clear();
                    changes.put(ShareAccountApiConstants.additionalshares_paramname, transaction.getId());
                    Set<ShareAccountTransaction> transactions = new HashSet<>();
                    transactions.add(transaction);
                    this.journalEntryWritePlatformService.createJournalEntriesForShares(populateJournalEntries(account, transactions));
                    this.shareAccountRepository.saveAndFlush(account);
                }
            }

            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult approveShareAccount(Long accountId, JsonCommand jsonCommand) {

        try {
            final boolean skipSavingsBalanceCheckOnTransfer = true;
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndApprove(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.save(account);
                final String noteText = jsonCommand.stringValueOfParameterNamed("note");
                if (StringUtils.isNotBlank(noteText)) {
                    final Note note = Note.shareNote(account, noteText);
                    changes.put("note", noteText);
                    this.noteRepository.save(note);
                }
            }
            Set<ShareAccountTransaction> transactions = account.getShareAccountTransactions();
            Set<ShareAccountTransaction> journalTransactions = new HashSet<>();
            Long totalSubsribedShares = Long.valueOf(0);

            for (ShareAccountTransaction transaction : transactions) {
                if (transaction.isActive() && transaction.isPurchasTransaction()) {
                    journalTransactions.add(transaction);
                    totalSubsribedShares += transaction.getTotalShares();
                    if (transaction.isUsingSavings()) {
                        if (transaction.getSavingsTransactionId() != null) {
                            // Get the hold transaction
                            SavingsAccountTransaction holdTransaction = this.savingsAccountTransactionRepository
                                    .findOneByIdAndSavingsAccountId(transaction.getSavingsTransactionId(),
                                            account.getSavingsAccount().getId());

                            if (holdTransaction != null) {
                                // Release the hold amount from the savings account
                                account.getSavingsAccount().releaseOnHoldAmount(holdTransaction.getAmount());

                                // Create release transaction with the share purchase date
                                SavingsAccountTransaction releaseTransaction = SavingsAccountTransaction
                                        .releaseAmount(holdTransaction, transaction.getPurchasedDate());
                                this.savingsAccountTransactionRepository.saveAndFlush(releaseTransaction);

                                // Link the release transaction to the hold transaction
                                holdTransaction.updateReleaseId(releaseTransaction.getId());
                                this.savingsAccountTransactionRepository.saveAndFlush(holdTransaction);
                            }
                        }

                        // Now perform the transfer
                        final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(
                                transaction.getPurchasedDate(),
                                transaction.amount(),
                                PortfolioAccountType.SAVINGS,
                                PortfolioAccountType.SHARES,
                                account.getSavingsAccount().getId(),
                                account.getId(),
                                "Share Purchase",
                                null, null, null, null, null, null, null,
                                AccountTransferType.ACCOUNT_TRANSFER.getValue(),
                                null, null,
                                org.apache.fineract.infrastructure.core.domain.ExternalId.empty(),
                                null, null,
                                account.getSavingsAccount(),
                                Boolean.TRUE, skipSavingsBalanceCheckOnTransfer);
                        //this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
                    }
                }
            }
            ShareProduct shareProduct = account.getShareProduct();
            recalculateShareProductSummary(shareProduct);

            //this.journalEntryWritePlatformService.createJournalEntriesForShares(populateJournalEntries(account, journalTransactions));

            businessEventNotifierService.notifyPostBusinessEvent(new ShareAccountApproveBusinessEvent(account));

            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult rejectShareAccount(Long accountId, JsonCommand jsonCommand) {
        try {
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndReject(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.saveAndFlush(account);
                final String noteText = jsonCommand.stringValueOfParameterNamed("note");
                if (StringUtils.isNotBlank(noteText)) {
                    final Note note = Note.shareNote(account, noteText);
                    changes.put("note", noteText);
                    this.noteRepository.save(note);
                }
            }
            Set<ShareAccountTransaction> transactions = account.getShareAccountTransactions();
            Set<ShareAccountTransaction> journalTransactions = new HashSet<>();
            for (ShareAccountTransaction transaction : transactions) {
                if (transaction.isActive() && !transaction.isChargeTransaction()) {
                    journalTransactions.add(transaction);
                    if (transaction.isUsingSavings() && transaction.getSavingsTransactionId() != null) {
                        this.savingsAccountWritePlatformService.releaseAmount(account.getSavingsAccount().getId(),
                                transaction.getSavingsTransactionId());
                    }
                }
            }

            this.journalEntryWritePlatformService.createJournalEntriesForShares(populateJournalEntries(account, journalTransactions));
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult undoApproveShareAccount(Long accountId, JsonCommand jsonCommand) {
        try {
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndUndoApprove(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.save(account);
                final String noteText = jsonCommand.stringValueOfParameterNamed("note");
                if (StringUtils.isNotBlank(noteText)) {
                    final Note note = Note.shareNote(account, noteText);
                    changes.put("note", noteText);
                    this.noteRepository.save(note);
                }
            }

            Set<ShareAccountTransaction> transactions = account.getShareAccountTransactions();
            ArrayList<Long> journalEntryTransactions = new ArrayList<>();
            for (ShareAccountTransaction transaction : transactions) {
                if (transaction.isActive() && !transaction.isChargeTransaction()) {
                    journalEntryTransactions.add(transaction.getId());
                }
            }
            LocalDate transactionDate = DateUtils.getBusinessLocalDate();
            this.journalEntryWritePlatformService.revertShareAccountJournalEntries(journalEntryTransactions, transactionDate);
            journalEntryWritePlatformService.createJournalEntriesForShares(
                    populateJournalEntries(account, account.getPendingForApprovalSharePurchaseTransactions()));
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult activateShareAccount(Long accountId, JsonCommand jsonCommand) {

        try {
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndActivate(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.save(account);
            }
            this.journalEntryWritePlatformService
                    .createJournalEntriesForShares(populateJournalEntries(account, account.getChargeTransactions()));
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public CommandProcessingResult approveAdditionalShares(Long accountId, JsonCommand jsonCommand) {

        try {
            final boolean skipSavingsBalanceCheckOnTransfer = resolveSkipSavingsBalanceCheckOnShareApproval(jsonCommand);
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndApproveAddtionalShares(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.saveAndFlush(account);
                ArrayList<Long> transactionIds = (ArrayList<Long>) changes.get(ShareAccountApiConstants.requestedshares_paramname);
                Long totalSubscribedShares = Long.valueOf(0);
                if (transactionIds != null) {
                    Set<ShareAccountTransaction> transactions = new HashSet<>();
                    for (Long id : transactionIds) {
                        ShareAccountTransaction transaction = account.retrievePurchasedShares(id);
                        transactions.add(transaction);
                        totalSubscribedShares += transaction.getTotalShares();
                        if (transaction.isUsingSavings()) {
                            if (transaction.getSavingsTransactionId() != null) {
                                // Get the hold transaction
                                SavingsAccountTransaction holdTransaction = this.savingsAccountTransactionRepository
                                        .findOneByIdAndSavingsAccountId(transaction.getSavingsTransactionId(),
                                                account.getSavingsAccount().getId());

                                if (holdTransaction != null) {
                                    // Release the hold amount from the savings account
                                    account.getSavingsAccount().releaseOnHoldAmount(holdTransaction.getAmount());

                                    // Create release transaction with the share purchase date
                                    SavingsAccountTransaction releaseTransaction = SavingsAccountTransaction
                                            .releaseAmount(holdTransaction, transaction.getPurchasedDate());
                                    this.savingsAccountTransactionRepository.saveAndFlush(releaseTransaction);

                                    // Link the release transaction to the hold transaction
                                    holdTransaction.updateReleaseId(releaseTransaction.getId());
                                    this.savingsAccountTransactionRepository.saveAndFlush(holdTransaction);
                                }
                            }

                            // Now perform the transfer
                            final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(
                                    transaction.getPurchasedDate(),
                                    transaction.amount(),
                                    PortfolioAccountType.SAVINGS,
                                    PortfolioAccountType.SHARES,
                                    account.getSavingsAccount().getId(),
                                    account.getId(),
                                    "Additional Share Purchase",
                                    null, null, null, null, null, null, null,
                                    AccountTransferType.ACCOUNT_TRANSFER.getValue(),
                                    null, null,
                                    org.apache.fineract.infrastructure.core.domain.ExternalId.empty(),
                                    null, null,
                                    account.getSavingsAccount(),
                                    Boolean.TRUE, skipSavingsBalanceCheckOnTransfer);
                            this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
                        }
                    }
                    this.journalEntryWritePlatformService.createJournalEntriesForShares(populateJournalEntries(account, transactions));
                }
                if (!totalSubscribedShares.equals(Long.valueOf(0))) {
                    ShareProduct shareProduct = account.getShareProduct();
                    recalculateShareProductSummary(shareProduct);
                }
            }
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public CommandProcessingResult rejectAdditionalShares(Long accountId, JsonCommand jsonCommand) {
        try {
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndRejectAddtionalShares(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.saveAndFlush(account);
                ArrayList<Long> transactionIds = (ArrayList<Long>) changes.get(ShareAccountApiConstants.requestedshares_paramname);
                if (transactionIds != null) {
                    Set<ShareAccountTransaction> transactions = new HashSet<>();
                    for (Long id : transactionIds) {
                        ShareAccountTransaction transaction = account.retrievePurchasedShares(id);
                        transactions.add(transaction);
                        if (transaction.isUsingSavings() && transaction.getSavingsTransactionId() != null) {
                            this.savingsAccountWritePlatformService.releaseAmount(account.getSavingsAccount().getId(),
                                    transaction.getSavingsTransactionId());
                        }
                    }
                    this.journalEntryWritePlatformService.createJournalEntriesForShares(populateJournalEntries(account, transactions));
                }
            }
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult redeemShares(Long accountId, JsonCommand jsonCommand) {
        try {
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndRedeemShares(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.saveAndFlush(account);
                ShareAccountTransaction transaction = (ShareAccountTransaction) changes
                        .get(ShareAccountApiConstants.requestedshares_paramname);
                // after saving, entity will have different object. So need to
                // retrieve the entity object
                transaction = account.getShareAccountTransaction(transaction);
                Long redeemShares = transaction.getTotalShares();
                ShareProduct shareProduct = account.getShareProduct();
                // remove the redeem shares from total subscribed shares
                recalculateShareProductSummary(shareProduct);

                Set<ShareAccountTransaction> transactions = new HashSet<>();
                transactions.add(transaction);
                this.journalEntryWritePlatformService.createJournalEntriesForShares(populateJournalEntries(account, transactions));
                changes.clear();
                changes.put(ShareAccountApiConstants.requestedshares_paramname, transaction.getId());

            }
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    @Override
    public CommandProcessingResult closeShareAccount(Long accountId, JsonCommand jsonCommand) {
        try {
            ShareAccount account = this.shareAccountRepository.findOneWithNotFoundDetection(accountId);
            Map<String, Object> changes = this.accountDataSerializer.validateAndClose(jsonCommand, account);
            if (!changes.isEmpty()) {
                this.shareAccountRepository.saveAndFlush(account);
                final String noteText = jsonCommand.stringValueOfParameterNamed("note");
                if (StringUtils.isNotBlank(noteText)) {
                    final Note note = Note.shareNote(account, noteText);
                    changes.put("note", noteText);
                    this.noteRepository.save(note);
                }
                ShareAccountTransaction transaction = (ShareAccountTransaction) changes
                        .get(ShareAccountApiConstants.requestedshares_paramname);
                transaction = account.getShareAccountTransaction(transaction);
                Set<ShareAccountTransaction> transactions = new HashSet<>();
                transactions.add(transaction);
                this.journalEntryWritePlatformService.createJournalEntriesForShares(populateJournalEntries(account, transactions));
                changes.clear();
                changes.put(ShareAccountApiConstants.requestedshares_paramname, transaction.getId());

            }
            return new CommandProcessingResultBuilder() //
                    .withCommandId(jsonCommand.commandId()) //
                    .withEntityId(accountId) //
                    .with(changes) //
                    .build();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(jsonCommand, dve.getMostSpecificCause(), dve);
            return CommandProcessingResult.empty();
        }
    }

    private void handleDataIntegrityIssues(final JsonCommand command, final Throwable realCause, final Exception dve) {
        throw ErrorHandler.getMappable(dve, "error.msg.shareaccount.unknown.data.integrity.issue",
                "Unknown data integrity issue with resource.");
    }

    /**
     * When {@code skipSavingsBalanceCheckOnShareApproval=true} in the approve command JSON, the savings withdrawal for the
     * share purchase sets {@link AccountTransferDTO#isExceptionForBalanceCheck()} so the savings layer skips insufficient-balance
     * enforcement (includings post-checks that previously ignored the flag; see {@code SavingsAccount#validateAccountBalanceDoesNotBecomeNegative}).
     * Use only for recovery when balance was already used before approval. Same API permission rules as share approval apply.
     */
    private boolean resolveSkipSavingsBalanceCheckOnShareApproval(final JsonCommand jsonCommand) {
        if (!jsonCommand.parameterExists(ShareAccountApiConstants.skip_savings_balance_check_on_share_approval_paramname)) {
            return false;
        }
        return Boolean.TRUE.equals(jsonCommand
                .booleanObjectValueOfParameterNamed(ShareAccountApiConstants.skip_savings_balance_check_on_share_approval_paramname));
    }

    private void recalculateShareProductSummary(final ShareProduct shareProduct) {
        Long totalSubscribedShares = this.shareAccountRepository.getTotalSubscribedShares(shareProduct.getId());
        shareProduct.recalculateSummary(totalSubscribedShares);
        this.shareProductRepository.save(shareProduct);
    }
}
