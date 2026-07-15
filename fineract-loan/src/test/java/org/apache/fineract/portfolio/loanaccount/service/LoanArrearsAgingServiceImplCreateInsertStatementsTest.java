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
package org.apache.fineract.portfolio.loanaccount.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.LoanSchedulePeriodData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies original-schedule arrears insert criteria for Update Loan Arrears Ageing (Path B).
 * Interest/fee/penalty-only overdue must produce a row, matching Path A / event-driven updates.
 */
class LoanArrearsAgingServiceImplCreateInsertStatementsTest {

    private static final MathContext MATH_CONTEXT = new MathContext(19, RoundingMode.HALF_EVEN);
    private static MockedStatic<MoneyHelper> moneyHelper;

    private LoanArrearsAgingServiceImpl underTest;
    private LocalDate businessDate;
    private LocalDate dueDate;

    @BeforeAll
    static void initMoneyHelper() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(MATH_CONTEXT);
    }

    @AfterAll
    static void closeMoneyHelper() {
        moneyHelper.close();
    }

    @BeforeEach
    void setUp() {
        businessDate = LocalDate.of(2024, 6, 15);
        dueDate = LocalDate.of(2024, 5, 1);
        ThreadLocalContextUtil.setBusinessDates(
                new HashMap<>(new EnumMap<>(Map.of(BusinessDateType.BUSINESS_DATE, businessDate))));

        underTest = new LoanArrearsAgingServiceImpl(mock(JdbcTemplate.class), mock(BusinessEventNotifierService.class),
                mock(DatabaseSpecificSQLGenerator.class));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void createInsertStatements_includesInterestOnlyOverdue() {
        final Long loanId = 101L;
        final LoanSchedulePeriodData base = LoanSchedulePeriodData.repaymentOnlyPeriod(1, dueDate.minusMonths(1), dueDate,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(9000), BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO);
        // Principal fully paid; interest still owed — previously dropped by principalOverdue > 0 gate
        final LoanSchedulePeriodData period = LoanSchedulePeriodData.withPaidDetail(base, false, BigDecimal.valueOf(1000),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        final Map<Long, List<LoanSchedulePeriodData>> scheduleDate = Map.of(loanId, List.of(period));
        final List<String> insertStatements = new ArrayList<>();

        underTest.createInsertStatements(insertStatements, scheduleDate, true);

        assertEquals(1, insertStatements.size());
        final String sql = insertStatements.get(0);
        assertTrue(sql.startsWith("INSERT INTO m_loan_arrears_aging"));
        assertTrue(sql.contains("101,0,50,0,0,50,'2024-05-01')"));
    }

    @Test
    void createInsertStatements_includesFeeOnlyOverdue() {
        final Long loanId = 102L;
        final LoanSchedulePeriodData base = LoanSchedulePeriodData.repaymentOnlyPeriod(1, dueDate.minusMonths(1), dueDate,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(9000), BigDecimal.ZERO, BigDecimal.valueOf(25), BigDecimal.ZERO);
        final LoanSchedulePeriodData period = LoanSchedulePeriodData.withPaidDetail(base, false, BigDecimal.valueOf(1000),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        final List<String> insertStatements = new ArrayList<>();
        underTest.createInsertStatements(insertStatements, Map.of(loanId, List.of(period)), true);

        assertEquals(1, insertStatements.size());
        assertTrue(insertStatements.get(0).contains("'2024-05-01'"));
        assertTrue(insertStatements.get(0).contains("25"));
    }

    @Test
    void createInsertStatements_includesPrincipalOverdue() {
        final Long loanId = 103L;
        final LoanSchedulePeriodData base = LoanSchedulePeriodData.repaymentOnlyPeriod(1, dueDate.minusMonths(1), dueDate,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(9000), BigDecimal.valueOf(40), BigDecimal.ZERO, BigDecimal.ZERO);
        final LoanSchedulePeriodData period = LoanSchedulePeriodData.withPaidDetail(base, false, BigDecimal.valueOf(200),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        final List<String> insertStatements = new ArrayList<>();
        underTest.createInsertStatements(insertStatements, Map.of(loanId, List.of(period)), true);

        assertEquals(1, insertStatements.size());
        final String sql = insertStatements.get(0);
        assertTrue(sql.contains("800"));
        assertTrue(sql.contains("40"));
        assertTrue(sql.contains("'2024-05-01'"));
    }

    @Test
    void createInsertStatements_skipsFullyPaidPeriod() {
        final Long loanId = 104L;
        final LoanSchedulePeriodData base = LoanSchedulePeriodData.repaymentOnlyPeriod(1, dueDate.minusMonths(1), dueDate,
                BigDecimal.valueOf(1000), BigDecimal.valueOf(9000), BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO);
        final LoanSchedulePeriodData period = LoanSchedulePeriodData.withPaidDetail(base, true, BigDecimal.valueOf(1000),
                BigDecimal.valueOf(50), BigDecimal.ZERO, BigDecimal.ZERO);

        final List<String> insertStatements = new ArrayList<>();
        underTest.createInsertStatements(insertStatements, Map.of(loanId, List.of(period)), true);

        assertTrue(insertStatements.isEmpty());
    }
}
