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
package org.apache.fineract.integrationtests;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.savings.AccountTransferHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsProductHelper;
import org.apache.fineract.integrationtests.common.shares.ShareAccountHelper;
import org.apache.fineract.integrationtests.common.shares.ShareAccountTransactionHelper;
import org.apache.fineract.integrationtests.common.shares.ShareProductHelper;
import org.apache.fineract.integrationtests.common.shares.ShareProductTransactionHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShareAccountTransferTest {

    private static final Logger LOG = LoggerFactory.getLogger(ShareAccountTransferTest.class);
    public static final String MINIMUM_OPENING_BALANCE = "1000.0";
    public static final String ACCOUNT_TYPE_INDIVIDUAL = "INDIVIDUAL";

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private SavingsAccountHelper savingsAccountHelper;
    private AccountTransferHelper accountTransferHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization",
                "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();

        this.savingsAccountHelper = new SavingsAccountHelper(this.requestSpec, this.responseSpec);
        this.accountTransferHelper = new AccountTransferHelper(this.requestSpec, this.responseSpec);
    }

    @Test
    public void testFromSavingsToShareAccountTransfer() {
        LOG.info(
                "------------------------------TEST: SAVINGS TO SHARE TRANSFER---------------------------------------");

        // 1. Create Client
        final Integer clientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientId);

        // 2. Create Savings Account (Source)
        final Integer savingsId = createSavingsAccount(clientId, "1000.0");
        Assertions.assertNotNull(savingsId);

        // 3. Create Share Product
        final Integer shareProductId = createShareProduct();
        Assertions.assertNotNull(shareProductId);

        // 4. Create Share Account
        final Integer shareAccountId = createShareAccount(clientId, shareProductId, savingsId);
        Assertions.assertNotNull(shareAccountId);

        // Approve and Activate Share Account
        approveShareAccount(shareAccountId);
        activateShareAccount(shareAccountId);

        // 5. Transfer from Savings to Share
        // Unit price is 2.0 (default in helper). 10 shares = 20.0
        String transferAmount = "20.0";

        Integer transferId = this.accountTransferHelper.accountTransfer(clientId, savingsId, clientId, shareAccountId,
                "2", "3", transferAmount); // 2=Savings, 3=Share
        Assertions.assertNotNull(transferId);

        // 6. Verify Savings Balance (decreased by 20)
        HashMap savingsSummary = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balance = (Float) savingsSummary.get("accountBalance");
        Assertions.assertEquals(980.0f, balance, 0.001f);

        // 7. Verify Share Account (new transaction)
        Map<String, Object> shareAccountData = ShareAccountTransactionHelper.retrieveShareAccount(shareAccountId,
                requestSpec, responseSpec);
        List<Map<String, Object>> transactions = (List<Map<String, Object>>) shareAccountData.get("purchasedShares");

        boolean transferFound = false;
        for (Map<String, Object> txn : transactions) {
            Map<String, Object> type = (Map<String, Object>) txn.get("type");
            if ("purchasedSharesType.purchased".equals(type.get("code"))) {
                Double amount = (Double) txn.get("amount");
                if (Math.abs(amount - 20.0) < 0.001) {
                    transferFound = true;
                    Integer shares = (Integer) txn.get("numberOfShares");
                    Assertions.assertEquals(10, shares.intValue());
                }
            }
        }
        Assertions.assertTrue(transferFound, "Transfer transaction not found in share account");
    }

    @Test
    public void testFromShareToSavingsAccountTransfer() {
        LOG.info(
                "------------------------------TEST: SHARE TO SAVINGS TRANSFER---------------------------------------");

        // 1. Create Client
        final Integer clientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientId);

        // 2. Create Savings Account (Destination)
        final Integer savingsId = createSavingsAccount(clientId, "1000.0");
        Assertions.assertNotNull(savingsId);

        // 3. Create Share Product
        final Integer shareProductId = createShareProduct();
        Assertions.assertNotNull(shareProductId);

        // 4. Create Share Account with sufficient shares
        // createShareAccount sets 100 shares at 2.0 each = 200.0 value
        final Integer shareAccountId = createShareAccount(clientId, shareProductId, savingsId);
        Assertions.assertNotNull(shareAccountId);
        approveShareAccount(shareAccountId);
        activateShareAccount(shareAccountId);

        // 5. Transfer from Share to Savings
        // Transfer 20.0 worth (10 shares)
        String transferAmount = "20.0";

        Integer transferId = this.accountTransferHelper.accountTransfer(clientId, shareAccountId, clientId, savingsId,
                "3", "2", transferAmount); // 3=Share, 2=Savings
        Assertions.assertNotNull(transferId);

        // 6. Verify Savings Balance (increased by 20)
        HashMap savingsSummary = this.savingsAccountHelper.getSavingsSummary(savingsId);
        Float balance = (Float) savingsSummary.get("accountBalance");
        Assertions.assertEquals(1020.0f, balance, 0.001f);

        // 7. Verify Share Account (shares redeemed)
        Map<String, Object> shareAccountData = ShareAccountTransactionHelper.retrieveShareAccount(shareAccountId,
                requestSpec, responseSpec);
        Map<String, Object> summary = (Map<String, Object>) shareAccountData.get("summary");
        // Initial 100 shares - 10 transferred = 90
        Integer totalApprovedShares = (Integer) summary.get("totalApprovedShares"); // Gson usually returns Double for
                                                                                    // numbers unless configured, but
                                                                                    // integration tests often cast to
                                                                                    // sensible types. Fineract helpers
                                                                                    // sometimes return primitive
                                                                                    // wrappers.
        // Actually Fineract common helper returns Map<String, Object>. The server
        // returns JSON numbers which Gson might parse as Double.
        // Let's be safe.
        // Warning: In ShareAccountIntegrationTests, it asserts "25" string.
        // "Assertions.assertEquals("25",
        // String.valueOf(summaryMap.get("totalApprovedShares")));"
        // So I should stick to String.valueOf for safety as well.
        Assertions.assertEquals("90", String.valueOf(summary.get("totalApprovedShares")));
    }

    @Test
    public void testFromShareToShareAccountTransfer() {
        LOG.info("------------------------------TEST: SHARE TO SHARE TRANSFER---------------------------------------");

        // 1. Create Client
        final Integer clientId = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        Assertions.assertNotNull(clientId);

        // 2. Create Savings Account (Linked for both)
        final Integer savingsId = createSavingsAccount(clientId, "1000.0");
        Assertions.assertNotNull(savingsId);

        // 3. Create Share Product
        final Integer shareProductId = createShareProduct();

        // 4. Create Source Share Account
        final Integer sourceShareAccountId = createShareAccount(clientId, shareProductId, savingsId);
        approveShareAccount(sourceShareAccountId);
        activateShareAccount(sourceShareAccountId);

        // 5. Create Destination Share Account
        final Integer destShareAccountId = createShareAccount(clientId, shareProductId, savingsId);
        approveShareAccount(destShareAccountId);
        activateShareAccount(destShareAccountId);

        // 6. Transfer 10 shares (worth 20.0) from Source to Dest
        String transferAmount = "20.0";

        Integer transferId = this.accountTransferHelper.accountTransfer(clientId, sourceShareAccountId, clientId,
                destShareAccountId,
                "3", "3", transferAmount); // 3=Share, 3=Share
        Assertions.assertNotNull(transferId);

        // 7. Verify Source Share Account (100 - 10 = 90)
        Map<String, Object> sourceData = ShareAccountTransactionHelper.retrieveShareAccount(sourceShareAccountId,
                requestSpec, responseSpec);
        Map<String, Object> sourceSummary = (Map<String, Object>) sourceData.get("summary");
        Assertions.assertEquals("90", String.valueOf(sourceSummary.get("totalApprovedShares")));

        // 8. Verify Destination Share Account (100 + 10 = 110)
        Map<String, Object> destData = ShareAccountTransactionHelper.retrieveShareAccount(destShareAccountId,
                requestSpec, responseSpec);
        Map<String, Object> destSummary = (Map<String, Object>) destData.get("summary");
        Assertions.assertEquals("110", String.valueOf(destSummary.get("totalApprovedShares")));
    }

    // Helpers

    private Integer createSavingsAccount(Integer clientId, String minBalance) {
        final Integer savingsProductID = createSavingsProduct(minBalance);
        Assertions.assertNotNull(savingsProductID);
        final Integer savingsId = this.savingsAccountHelper.applyForSavingsApplication(clientId, savingsProductID,
                ACCOUNT_TYPE_INDIVIDUAL);
        Assertions.assertNotNull(savingsId);
        this.savingsAccountHelper.approveSavings(savingsId);
        this.savingsAccountHelper.activateSavings(savingsId);
        return savingsId;
    }

    private Integer createSavingsProduct(String minBalance) {
        final String savingsProductJSON = new SavingsProductHelper().withMinimumOpenningBalance(minBalance).build();
        return SavingsProductHelper.createSavingsProduct(savingsProductJSON, requestSpec, responseSpec);
    }

    private Integer createShareProduct() {
        return new ShareProductHelper().build() == null ? null
                : ShareProductTransactionHelper.createShareProduct(new ShareProductHelper().build(), requestSpec,
                        responseSpec);
    }

    private Integer createShareAccount(Integer clientId, Integer productId, Integer savingsId) {
        final String shareAccountJson = new ShareAccountHelper()
                .withClientId(String.valueOf(clientId))
                .withProductId(String.valueOf(productId))
                .withSavingsAccountId(String.valueOf(savingsId))
                .withRequestedShares("100") // 100 shares * 2.0 = 200.0
                .withSubmittedDate("01 January 2023")
                .build();
        return ShareAccountTransactionHelper.createShareAccount(shareAccountJson, requestSpec, responseSpec);
    }

    private void approveShareAccount(Integer shareAccountId) {
        Map<String, Object> approveMap = new HashMap<>();
        approveMap.put("note", "Approval");
        approveMap.put("dateFormat", "dd MMMM yyyy");
        approveMap.put("approvedDate", "01 January 2023");
        approveMap.put("locale", "en");
        ShareAccountTransactionHelper.postCommand("approve", shareAccountId,
                new com.google.gson.Gson().toJson(approveMap), requestSpec, responseSpec);
    }

    private void activateShareAccount(Integer shareAccountId) {
        Map<String, Object> activateMap = new HashMap<>();
        activateMap.put("dateFormat", "dd MMMM yyyy");
        activateMap.put("activatedDate", "01 January 2023");
        activateMap.put("locale", "en");
        ShareAccountTransactionHelper.postCommand("activate", shareAccountId,
                new com.google.gson.Gson().toJson(activateMap), requestSpec, responseSpec);
    }
}
