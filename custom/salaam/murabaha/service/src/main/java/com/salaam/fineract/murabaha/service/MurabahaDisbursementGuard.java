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
package com.salaam.fineract.murabaha.service;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanDisbursalBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Blocks any disbursement of a Murabaha loan until the bank's purchase of the asset is recorded, whichever API or
 * screen triggers it. The check runs inside the disbursement transaction, so a rejected disbursement leaves no trace.
 */
@Component
@RequiredArgsConstructor
public class MurabahaDisbursementGuard implements InitializingBean {

    private final BusinessEventNotifierService businessEventNotifierService;
    private final MurabahaService murabahaService;

    @Override
    public void afterPropertiesSet() {
        businessEventNotifierService.addPreBusinessEventListener(LoanDisbursalBusinessEvent.class, event -> check(event.get()));
    }

    private void check(final Loan loan) {
        if (murabahaService.isMurabahaProduct(loan.getLoanProduct().getId()) && !murabahaService.hasPurchase(loan.getId())) {
            throw new GeneralPlatformDomainRuleException("error.msg.murabaha.purchase.required",
                    "Murabaha loan " + loan.getId() + " cannot be disbursed before the bank's purchase of the asset is recorded", loan.getId());
        }
    }
}
