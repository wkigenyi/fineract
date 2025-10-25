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

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformServiceUnavailableException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.apache.log4j.Logger;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor
public class StandingInstructionItemWriter implements ItemWriter<AccountTransferRequest> {

    private static final Logger LOG = Logger.getLogger(StandingInstructionItemWriter.class);
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;

    @Override
    public void write(Chunk<? extends AccountTransferRequest> chunk) throws Exception {
        List<Throwable> errors = new ArrayList<>();
        for (AccountTransferRequest transferRequest : chunk.getItems()) {
            Long instructionId = transferRequest.getInstructionId();
            AccountTransferDTO dto = transferRequest.getAccountTransferDTO();
            try {
                boolean transferCompleted = transferAmount(errors, dto, instructionId);
                if (transferCompleted) {
                    final String updateQuery = "UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?";
                    jdbcTemplate.update(updateQuery, dto.getTransactionDate(), instructionId);
                }
            } catch (Throwable e) {
                LOG.error("Error processing transaction id " + instructionId, e);
            }

        }

    }

    private boolean transferAmount(final List<Throwable> errors, final AccountTransferDTO accountTransferDTO, final Long instructionId) {
        boolean transferCompleted = true;
        StringBuilder errorLog = new StringBuilder();
        StringBuilder updateQuery = new StringBuilder(
                "INSERT INTO m_account_transfer_standing_instructions_history (standing_instruction_id, " + sqlGenerator.escape("status")
                        + ", amount,execution_time, error_log) VALUES (");
        try {
            accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
        } catch (final PlatformApiDataValidationException e) {
            LOG.error(e.getMessage());
            errors.add(new Exception("Validation exception while transferring funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Validation exception while transferring funds ").append(e.getDefaultUserMessage());
        } catch (final InsufficientAccountBalanceException e) {
            LOG.error(e.getMessage());
            errors.add(new Exception("InsufficientAccountBalance Exception while transferring funds for standing Instruction id"
                    + instructionId + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("InsufficientAccountBalance Exception ");
        } catch (final AbstractPlatformServiceUnavailableException e) {
            LOG.error(e.getMessage());
            errors.add(new Exception("Platform exception while transferring funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Platform exception while transferring funds ").append(e.getDefaultUserMessage());
        } catch (Exception e) {
            LOG.error(e.getMessage());
            errors.add(new Exception("Unhandled System Exception while trasfering funds for standing Instruction id" + instructionId
                    + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Exception while transferring funds ").append(e.getMessage());

        }
        updateQuery.append(instructionId).append(",");
        if (!errorLog.isEmpty()) {
            transferCompleted = false;
            updateQuery.append("'failed'").append(",");
        } else {
            updateQuery.append("'success'").append(",");
        }
        updateQuery.append(accountTransferDTO.getTransactionAmount().doubleValue());
        updateQuery.append(", now(),");
        updateQuery.append("'").append(errorLog).append("')");
        jdbcTemplate.update(updateQuery.toString());
        return transferCompleted;
    }
}
