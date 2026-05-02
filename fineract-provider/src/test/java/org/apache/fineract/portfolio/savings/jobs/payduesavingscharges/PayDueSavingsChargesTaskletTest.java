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
package org.apache.fineract.portfolio.savings.jobs.payduesavingscharges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.savings.data.SavingsAccountAnnualFeeData;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.apache.fineract.portfolio.savings.service.SavingsAccountChargeReadPlatformService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;

class PayDueSavingsChargesTaskletTest {

    private SavingsAccountChargeReadPlatformService chargeReadPlatformService;
    private SavingsAccountWritePlatformService writePlatformService;
    private StepContribution contribution;
    private ChunkContext chunkContext;
    private PayDueSavingsChargesTasklet tasklet;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        chargeReadPlatformService = mock(SavingsAccountChargeReadPlatformService.class);
        writePlatformService = mock(SavingsAccountWritePlatformService.class);
        contribution = mock(StepContribution.class);
        chunkContext = mock(ChunkContext.class);
        tasklet = new PayDueSavingsChargesTasklet(chargeReadPlatformService, writePlatformService);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void isSkippableInsufficientBalanceValidation_trueWhenAllErrorsAreBalanceGoingNegative() {
        List<ApiParameterError> errors = List.of(ApiParameterError.parameterError(
                "validation.msg.savingsaccount.savingsAccountTransactionType.payCharge.results.in.balance.going.negative",
                "Failed data validation.", "."));
        assertTrue(PayDueSavingsChargesTasklet.isSkippableInsufficientBalanceValidation(new PlatformApiDataValidationException(errors)));
    }

    @Test
    void isSkippableInsufficientBalanceValidation_falseWhenErrorsEmpty() {
        assertFalse(PayDueSavingsChargesTasklet.isSkippableInsufficientBalanceValidation(new PlatformApiDataValidationException(new ArrayList<>())));
    }

    @Test
    void isSkippableInsufficientBalanceValidation_falseWhenMixedErrors() {
        List<ApiParameterError> errors = List.of(
                ApiParameterError.parameterError(
                        "validation.msg.savingsaccount.savingsAccountTransactionType.payCharge.results.in.balance.going.negative",
                        "balance", "."),
                ApiParameterError.parameterError("validation.msg.savingsaccount.transaction.invalid.account.is.closed", "closed", "."));
        assertFalse(PayDueSavingsChargesTasklet.isSkippableInsufficientBalanceValidation(new PlatformApiDataValidationException(errors)));
    }

    @Test
    void isSkippableInsufficientBalanceValidation_falseWhenOtherValidationOnly() {
        List<ApiParameterError> errors = List.of(ApiParameterError.parameterError(
                "validation.msg.savingsaccount.transaction.invalid.account.is.closed", "Failed data validation.", "."));
        assertFalse(PayDueSavingsChargesTasklet.isSkippableInsufficientBalanceValidation(new PlatformApiDataValidationException(errors)));
    }

    @Test
    void execute_whenNoDueCharges_finishesWithoutApplying() throws Exception {
        when(chargeReadPlatformService.retrieveChargesWithDue()).thenReturn(Collections.emptyList());

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertEquals(RepeatStatus.FINISHED, status);
        verify(writePlatformService, never()).applyChargeDue(anyLong(), anyLong());
    }

    @Test
    void execute_whenInsufficientAccountBalanceOnFirstCharge_continuesAndFinishes() throws Exception {
        SavingsAccountAnnualFeeData first = SavingsAccountAnnualFeeData.instance(1L, 10L, "ACC1", null);
        SavingsAccountAnnualFeeData second = SavingsAccountAnnualFeeData.instance(2L, 20L, "ACC2", null);
        when(chargeReadPlatformService.retrieveChargesWithDue()).thenReturn(List.of(first, second));
        doThrow(new InsufficientAccountBalanceException("transactionAmount", BigDecimal.ONE, null, BigDecimal.TEN)).when(writePlatformService)
                .applyChargeDue(1L, 10L);

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertEquals(RepeatStatus.FINISHED, status);
        verify(writePlatformService, times(1)).applyChargeDue(1L, 10L);
        verify(writePlatformService, times(1)).applyChargeDue(2L, 20L);
    }

    @Test
    void execute_whenBalanceGoingNegativeValidation_skipsAndFinishes() throws Exception {
        SavingsAccountAnnualFeeData first = SavingsAccountAnnualFeeData.instance(1L, 10L, "ACC1", null);
        SavingsAccountAnnualFeeData second = SavingsAccountAnnualFeeData.instance(2L, 20L, "ACC2", null);
        when(chargeReadPlatformService.retrieveChargesWithDue()).thenReturn(List.of(first, second));
        List<ApiParameterError> balanceErrors = List.of(ApiParameterError.parameterError(
                "validation.msg.savingsaccount.savingsAccountTransactionType.payCharge.results.in.balance.going.negative",
                "Failed data validation.", "."));
        doThrow(new PlatformApiDataValidationException(balanceErrors)).when(writePlatformService).applyChargeDue(1L, 10L);

        RepeatStatus status = tasklet.execute(contribution, chunkContext);

        assertEquals(RepeatStatus.FINISHED, status);
        verify(writePlatformService, times(1)).applyChargeDue(1L, 10L);
        verify(writePlatformService, times(1)).applyChargeDue(2L, 20L);
    }

    @Test
    void execute_whenOtherPlatformApiValidation_throwsJobExecutionException() {
        SavingsAccountAnnualFeeData data = SavingsAccountAnnualFeeData.instance(1L, 10L, "ACC1", null);
        when(chargeReadPlatformService.retrieveChargesWithDue()).thenReturn(List.of(data));
        List<ApiParameterError> errors = List.of(ApiParameterError.parameterError(
                "validation.msg.savingsaccount.transaction.invalid.account.is.closed", "Failed data validation.", "."));
        doThrow(new PlatformApiDataValidationException(errors)).when(writePlatformService).applyChargeDue(1L, 10L);

        assertThrows(JobExecutionException.class, () -> tasklet.execute(contribution, chunkContext));
    }

    @Test
    void execute_whenPlainRuntimeException_throwsJobExecutionException() {
        SavingsAccountAnnualFeeData data = SavingsAccountAnnualFeeData.instance(1L, 10L, "ACC1", null);
        when(chargeReadPlatformService.retrieveChargesWithDue()).thenReturn(List.of(data));
        doThrow(new IllegalStateException("unexpected")).when(writePlatformService).applyChargeDue(1L, 10L);

        assertThrows(JobExecutionException.class, () -> tasklet.execute(contribution, chunkContext));
    }
}
