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
package org.apache.fineract.integrationtests.common.shares;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ownership transfer of shares between clients (command=transfershares).
 */
@SuppressWarnings("unchecked")
public class ShareAccountTransferIntegrationTests {

    private static final String DATE = "01 January 2016";
    private static final String TRANSFER_DATE = "15 January 2016";

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private ResponseSpecification errorResponseSpec;
    private ShareProductHelper shareProductHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.errorResponseSpec = new ResponseSpecBuilder().expectStatusCode(400).build();
        this.shareProductHelper = new ShareProductHelper();
    }

    @Test
    public void testTransferSharesBetweenClientsSuccessfully() {
        final Integer productId = createShareProduct();
        final Integer fromClientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        final Integer toClientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);

        final Integer fromAccountId = createActiveShareAccount(fromClientId, productId, "25", "EXT-FROM-" + fromClientId);
        final Integer toAccountId = createActiveShareAccount(toClientId, productId, "10", "EXT-TO-" + toClientId);

        final Map<String, Object> transferResponse = ShareAccountTransactionHelper.transferShares(fromAccountId, toAccountId, TRANSFER_DATE,
                "5", this.requestSpec, this.responseSpec);
        assertNotNull(transferResponse.get("resourceId"));
        assertEquals(toAccountId.longValue(), ((Number) ((Map<String, Object>) transferResponse.get("changes")).get("toShareAccountId"))
                .longValue());

        final Map<String, Object> fromAccount = ShareAccountTransactionHelper.retrieveShareAccount(fromAccountId, this.requestSpec,
                this.responseSpec);
        final Map<String, Object> toAccount = ShareAccountTransactionHelper.retrieveShareAccount(toAccountId, this.requestSpec,
                this.responseSpec);

        assertEquals("20", String.valueOf(((Map<String, Object>) fromAccount.get("summary")).get("totalApprovedShares")));
        assertEquals("15", String.valueOf(((Map<String, Object>) toAccount.get("summary")).get("totalApprovedShares")));

        assertTrue(hasTransactionType((List<Map<String, Object>>) fromAccount.get("purchasedShares"),
                "purchasedSharesType.transferred.out"));
        assertTrue(hasTransactionType((List<Map<String, Object>>) toAccount.get("purchasedShares"), "purchasedSharesType.transferred.in"));

        final Long outTxnId = findTransactionIdByType((List<Map<String, Object>>) fromAccount.get("purchasedShares"),
                "purchasedSharesType.transferred.out");
        final Long inTxnId = findTransactionIdByType((List<Map<String, Object>>) toAccount.get("purchasedShares"),
                "purchasedSharesType.transferred.in");
        assertNotNull(outTxnId);
        assertNotNull(inTxnId);
        assertEquals(inTxnId, findLinkedTransactionId((List<Map<String, Object>>) fromAccount.get("purchasedShares"), outTxnId));
        assertEquals(outTxnId, findLinkedTransactionId((List<Map<String, Object>>) toAccount.get("purchasedShares"), inTxnId));
    }

    @Test
    public void testCannotTransferMoreSharesThanHeld() {
        final Integer productId = createShareProduct();
        final Integer fromClientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        final Integer toClientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);

        final Integer fromAccountId = createActiveShareAccount(fromClientId, productId, "25", "EXT-FROM-" + fromClientId);
        final Integer toAccountId = createActiveShareAccount(toClientId, productId, "10", "EXT-TO-" + toClientId);

        final Map<String, Object> errorResponse = ShareAccountTransactionHelper.transferShares(fromAccountId, toAccountId, TRANSFER_DATE,
                "100", this.requestSpec, this.errorResponseSpec);
        assertErrorCode(errorResponse, "validation.msg.sharesaccount.cannot.be.redeemed.due.to.insufficient.shares");

        final Map<String, Object> fromAccount = ShareAccountTransactionHelper.retrieveShareAccount(fromAccountId, this.requestSpec,
                this.responseSpec);
        assertEquals("25", String.valueOf(((Map<String, Object>) fromAccount.get("summary")).get("totalApprovedShares")));
    }

    @Test
    public void testCannotTransferToSameAccount() {
        final Integer productId = createShareProduct();
        final Integer clientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        final Integer accountId = createActiveShareAccount(clientId, productId, "25", "EXT-SAME-" + clientId);

        final Map<String, Object> errorResponse = ShareAccountTransactionHelper.transferShares(accountId, accountId, TRANSFER_DATE, "5",
                this.requestSpec, this.errorResponseSpec);
        assertErrorCode(errorResponse, "validation.msg.sharesaccount.cannot.transfer.to.same.account");
    }

    @Test
    public void testCannotTransferBetweenAccountsOfSameClient() {
        final Integer productId = createShareProduct();
        final Integer clientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        final Integer fromAccountId = createActiveShareAccount(clientId, productId, "25", "EXT-A-" + clientId);
        final Integer toAccountId = createActiveShareAccount(clientId, productId, "10", "EXT-B-" + clientId);

        final Map<String, Object> errorResponse = ShareAccountTransactionHelper.transferShares(fromAccountId, toAccountId, TRANSFER_DATE,
                "5", this.requestSpec, this.errorResponseSpec);
        assertErrorCode(errorResponse, "validation.msg.sharesaccount.transfer.must.be.between.different.clients");
    }

    @Test
    public void testCannotTransferWhenRecipientAccountIsNotActive() {
        final Integer productId = createShareProduct();
        final Integer fromClientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        final Integer toClientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);

        final Integer fromAccountId = createActiveShareAccount(fromClientId, productId, "25", "EXT-FROM-" + fromClientId);

        final Integer toSavingsId = SavingsAccountHelper.openSavingsAccount(this.requestSpec, this.responseSpec, toClientId, "1000");
        final Integer toAccountId = createShareAccount(toClientId, productId, toSavingsId, "10", "EXT-TO-PENDING-" + toClientId);
        // approve but do not activate
        approveShareAccount(toAccountId, DATE);

        final Map<String, Object> errorResponse = ShareAccountTransactionHelper.transferShares(fromAccountId, toAccountId, TRANSFER_DATE,
                "5", this.requestSpec, this.errorResponseSpec);
        assertErrorCode(errorResponse, "validation.msg.sharesaccount.transfer.to.account.not.active");
    }

    private Integer createShareProduct() {
        return ShareProductTransactionHelper.createShareProduct(this.shareProductHelper.build(), this.requestSpec, this.responseSpec);
    }

    private Integer createActiveShareAccount(final Integer clientId, final Integer productId, final String requestedShares,
            final String externalId) {
        final Integer savingsAccountId = SavingsAccountHelper.openSavingsAccount(this.requestSpec, this.responseSpec, clientId, "1000");
        final Integer shareAccountId = createShareAccount(clientId, productId, savingsAccountId, requestedShares, externalId);
        approveShareAccount(shareAccountId, DATE);
        activateShareAccount(shareAccountId, DATE);
        return shareAccountId;
    }

    private Integer createShareAccount(final Integer clientId, final Integer productId, final Integer savingsAccountId,
            final String requestedShares, final String externalId) {
        final String json = new ShareAccountHelper().withClientId(String.valueOf(clientId)).withProductId(String.valueOf(productId))
                .withExternalId(externalId).withSavingsAccountId(String.valueOf(savingsAccountId)).withSubmittedDate(DATE)
                .withApplicationDate(DATE).withRequestedShares(requestedShares).build();
        return ShareAccountTransactionHelper.createShareAccount(json, this.requestSpec, this.responseSpec);
    }

    private void approveShareAccount(final Integer shareAccountId, final String approvalDate) {
        final Map<String, Object> approveMap = new HashMap<>();
        approveMap.put("note", "Approved for transfer test");
        approveMap.put("dateFormat", "dd MMMM yyyy");
        approveMap.put("approvedDate", approvalDate);
        approveMap.put("locale", "en");
        ShareAccountTransactionHelper.postCommand("approve", shareAccountId, new Gson().toJson(approveMap), this.requestSpec,
                this.responseSpec);
    }

    private void activateShareAccount(final Integer shareAccountId, final String activationDate) {
        final Map<String, Object> activateMap = new HashMap<>();
        activateMap.put("dateFormat", "dd MMMM yyyy");
        activateMap.put("activatedDate", activationDate);
        activateMap.put("locale", "en");
        ShareAccountTransactionHelper.postCommand("activate", shareAccountId, new Gson().toJson(activateMap), this.requestSpec,
                this.responseSpec);
    }

    private void assertErrorCode(final Map<String, Object> errorResponse, final String expectedCode) {
        final List<Map<String, Object>> errors = (List<Map<String, Object>>) errorResponse.get(CommonConstants.RESPONSE_ERROR);
        assertNotNull(errors);
        assertTrue(!errors.isEmpty());
        assertEquals(expectedCode, errors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE));
    }

    private boolean hasTransactionType(final List<Map<String, Object>> transactions, final String typeCode) {
        return findTransactionIdByType(transactions, typeCode) != null;
    }

    private Long findTransactionIdByType(final List<Map<String, Object>> transactions, final String typeCode) {
        for (final Map<String, Object> transaction : transactions) {
            final Map<String, Object> type = (Map<String, Object>) transaction.get("type");
            if (typeCode.equals(String.valueOf(type.get("code")))) {
                return ((Number) transaction.get("id")).longValue();
            }
        }
        return null;
    }

    private Long findLinkedTransactionId(final List<Map<String, Object>> transactions, final Long transactionId) {
        for (final Map<String, Object> transaction : transactions) {
            if (((Number) transaction.get("id")).longValue() == transactionId.longValue()) {
                final Object linked = transaction.get("linkedTransactionId");
                return linked == null ? null : ((Number) linked).longValue();
            }
        }
        return null;
    }
}
