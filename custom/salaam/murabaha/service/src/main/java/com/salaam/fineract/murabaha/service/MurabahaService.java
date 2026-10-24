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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.salaam.fineract.murabaha.data.MurabahaDealData;
import com.salaam.fineract.murabaha.data.MurabahaProductData;
import com.salaam.fineract.murabaha.data.MurabahaProductRequest;
import com.salaam.fineract.murabaha.data.MurabahaPurchaseRequest;
import com.salaam.fineract.murabaha.data.MurabahaRebateRequest;
import com.salaam.fineract.murabaha.data.MurabahaSaleRequest;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a Murabaha deal through its Shariah-required order: the bank buys the asset, then sells it to the customer at cost
 * plus a fixed margin. The sale reuses standard Fineract commands (disbursement and capitalized income) so that
 * schedules, accounting and daily profit recognition stay in core Fineract.
 */
@Service
@RequiredArgsConstructor
public class MurabahaService {

    public static final String STATUS_PURCHASED = "PURCHASED";
    public static final String STATUS_SOLD = "SOLD";

    private static final int LOAN_STATUS_APPROVED = 200;
    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final String LOCALE = "en";

    private final JdbcTemplate jdbcTemplate;
    private final PlatformSecurityContext context;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;

    @Transactional
    public MurabahaProductData registerProduct(final MurabahaProductRequest request) {
        context.authenticatedUser().validateHasPermissionTo("CREATE_MURABAHAPRODUCT");
        require(request.loanProductId() != null && request.clearingGlAccountId() != null && request.cashGlAccountId() != null
                && request.salePaymentTypeId() != null, "error.msg.murabaha.product.fields.required",
                "loanProductId, clearingGlAccountId, cashGlAccountId and salePaymentTypeId are required");
        require(count("SELECT COUNT(*) FROM m_product_loan WHERE id = ?", request.loanProductId()) == 1,
                "error.msg.murabaha.product.not.found", "Loan product {0} does not exist", request.loanProductId());
        require(count("SELECT COUNT(*) FROM acc_gl_account WHERE id IN (?, ?)", request.clearingGlAccountId(),
                request.cashGlAccountId()) == 2, "error.msg.murabaha.gl.not.found", "Clearing or cash GL account does not exist");
        require(count("""
                SELECT COUNT(*) FROM acc_product_mapping
                WHERE product_type = 1 AND product_id = ? AND payment_type = ? AND gl_account_id = ?
                """, request.loanProductId(), request.salePaymentTypeId(), request.clearingGlAccountId()) > 0,
                "error.msg.murabaha.sale.payment.type.not.mapped",
                "The loan product must map payment type {0} to the clearing GL account as its fund source", request.salePaymentTypeId());

        jdbcTemplate.update("DELETE FROM salaam_murabaha_product WHERE loan_product_id = ?", request.loanProductId());
        jdbcTemplate.update("""
                INSERT INTO salaam_murabaha_product (loan_product_id, clearing_gl_account_id, cash_gl_account_id, sale_payment_type_id)
                VALUES (?, ?, ?, ?)
                """, request.loanProductId(), request.clearingGlAccountId(), request.cashGlAccountId(), request.salePaymentTypeId());
        return retrieveProduct(request.loanProductId());
    }

    public List<MurabahaProductData> retrieveProducts() {
        context.authenticatedUser().validateHasReadPermission("MURABAHA");
        return jdbcTemplate.query(PRODUCT_SQL, (rs, i) -> new MurabahaProductData(rs.getLong("loan_product_id"), rs.getString("name"),
                rs.getLong("clearing_gl_account_id"), rs.getLong("cash_gl_account_id"), rs.getLong("sale_payment_type_id")));
    }

    public boolean isMurabahaProduct(final Long loanProductId) {
        return count("SELECT COUNT(*) FROM salaam_murabaha_product WHERE loan_product_id = ?", loanProductId) > 0;
    }

    public boolean hasPurchase(final Long loanId) {
        return count("SELECT COUNT(*) FROM salaam_murabaha_deal WHERE loan_id = ?", loanId) > 0;
    }

    @Transactional
    public MurabahaDealData recordPurchase(final Long loanId, final MurabahaPurchaseRequest request) {
        context.authenticatedUser().validateHasPermissionTo("PURCHASE_MURABAHA");
        final Map<String, Object> loan = loadLoan(loanId);
        final Long productId = ((Number) loan.get("product_id")).longValue();
        final MurabahaProductData product = retrieveProduct(productId);

        require(((Number) loan.get("loan_status_id")).intValue() == LOAN_STATUS_APPROVED, "error.msg.murabaha.loan.not.approved",
                "Loan {0} must be approved and not yet disbursed before the asset is purchased", loanId);
        require(!hasPurchase(loanId), "error.msg.murabaha.purchase.exists", "A purchase is already recorded for loan {0}", loanId);
        require(StringUtils.isNotBlank(request.assetDescription()) && StringUtils.isNotBlank(request.supplierName()),
                "error.msg.murabaha.purchase.asset.required", "Asset description and supplier name are required");
        require(isPositive(request.purchasePrice()), "error.msg.murabaha.purchase.price.invalid", "Purchase price must be positive");
        final BigDecimal approvedPrincipal = (BigDecimal) loan.get("approved_principal");
        require(request.purchasePrice().compareTo(approvedPrincipal) < 0, "error.msg.murabaha.purchase.price.exceeds.selling.price",
                "Purchase price must be below the approved selling price {0}", approvedPrincipal);
        final LocalDate purchaseDate = parseDate(request.purchaseDate(), "purchaseDate");

        final JsonObject entry = new JsonObject();
        entry.addProperty("officeId", ((Number) loan.get("office_id")).longValue());
        entry.addProperty("transactionDate", purchaseDate.toString());
        entry.addProperty("dateFormat", DATE_FORMAT);
        entry.addProperty("locale", LOCALE);
        entry.addProperty("currencyCode", (String) loan.get("currency_code"));
        entry.addProperty("referenceNumber", "MURABAHA-" + loanId);
        entry.addProperty("comments", StringUtils.abbreviate("Murabaha purchase: " + request.assetDescription() + " from "
                + request.supplierName() + (request.invoiceNumber() == null ? "" : ", invoice " + request.invoiceNumber()), 500));
        entry.add("debits", glLine(product.clearingGlAccountId(), request.purchasePrice()));
        entry.add("credits", glLine(product.cashGlAccountId(), request.purchasePrice()));
        final CommandProcessingResult journal = execute(new CommandWrapperBuilder().createJournalEntry(), entry);

        jdbcTemplate.update("""
                INSERT INTO salaam_murabaha_deal (loan_id, status, asset_description, supplier_name, invoice_number, purchase_price,
                    purchase_date, purchase_journal_entry_id, rebate_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """, loanId, STATUS_PURCHASED, request.assetDescription(), request.supplierName(), request.invoiceNumber(),
                request.purchasePrice(), purchaseDate, journal.getTransactionId());
        return retrieveDeal(loanId);
    }

    @Transactional
    public MurabahaDealData recordSale(final Long loanId, final MurabahaSaleRequest request) {
        context.authenticatedUser().validateHasPermissionTo("SELL_MURABAHA");
        final MurabahaDealData deal = retrieveDeal(loanId);
        require(STATUS_PURCHASED.equals(deal.status()), "error.msg.murabaha.sale.requires.purchase",
                "Loan {0} can only be sold after the purchase is recorded, and only once", loanId);
        require(isPositive(request.margin()), "error.msg.murabaha.margin.invalid", "Margin must be positive");
        final LocalDate saleDate = parseDate(request.saleDate(), "saleDate");
        require(!saleDate.isBefore(LocalDate.parse(deal.purchaseDate())), "error.msg.murabaha.sale.before.purchase",
                "The sale cannot be dated before the purchase");
        final BigDecimal approvedPrincipal = (BigDecimal) loadLoan(loanId).get("approved_principal");
        final BigDecimal sellingPrice = deal.purchasePrice().add(request.margin());
        require(sellingPrice.compareTo(approvedPrincipal) <= 0, "error.msg.murabaha.selling.price.exceeds.approved",
                "Cost plus margin ({0}) exceeds the approved selling price {1}", sellingPrice, approvedPrincipal);

        final JsonObject disbursement = new JsonObject();
        disbursement.addProperty("actualDisbursementDate", saleDate.toString());
        disbursement.addProperty("transactionAmount", deal.purchasePrice());
        disbursement.addProperty("paymentTypeId", retrieveProduct(deal.loanProductId()).salePaymentTypeId());
        disbursement.addProperty("note", "Murabaha sale at cost: " + deal.assetDescription());
        addDateAndLocale(disbursement);
        execute(new CommandWrapperBuilder().disburseLoanApplication(loanId), disbursement);

        final JsonObject capitalizedIncome = new JsonObject();
        capitalizedIncome.addProperty("transactionDate", saleDate.toString());
        capitalizedIncome.addProperty("transactionAmount", request.margin());
        capitalizedIncome.addProperty("note", "Murabaha margin");
        addDateAndLocale(capitalizedIncome);
        final CommandProcessingResult margin = execute(new CommandWrapperBuilder().addCapitalizedIncome(loanId), capitalizedIncome);

        jdbcTemplate.update("UPDATE salaam_murabaha_deal SET status = ?, sale_date = ?, margin = ?, capitalized_income_txn_id = ? WHERE loan_id = ?",
                STATUS_SOLD, saleDate, request.margin(), margin.getResourceId(), loanId);
        return retrieveDeal(loanId);
    }

    @Transactional
    public MurabahaDealData applyRebate(final Long loanId, final MurabahaRebateRequest request) {
        context.authenticatedUser().validateHasPermissionTo("REBATE_MURABAHA");
        final MurabahaDealData deal = retrieveDeal(loanId);
        require(STATUS_SOLD.equals(deal.status()), "error.msg.murabaha.rebate.requires.sale", "Loan {0} has not been sold yet", loanId);
        require(isPositive(request.amount()), "error.msg.murabaha.rebate.invalid", "Rebate amount must be positive");
        final BigDecimal totalRebate = deal.rebateAmount().add(request.amount());
        require(totalRebate.compareTo(deal.margin()) <= 0, "error.msg.murabaha.rebate.exceeds.margin",
                "Total rebate {0} cannot exceed the margin {1}", totalRebate, deal.margin());
        final LocalDate rebateDate = parseDate(request.rebateDate(), "rebateDate");

        final JsonObject adjustment = new JsonObject();
        adjustment.addProperty("transactionDate", rebateDate.toString());
        adjustment.addProperty("transactionAmount", request.amount());
        adjustment.addProperty("note", "Murabaha early settlement rebate (ibra)");
        addDateAndLocale(adjustment);
        execute(new CommandWrapperBuilder().capitalizedIncomeAdjustment(loanId, deal.capitalizedIncomeTransactionId()), adjustment);

        jdbcTemplate.update("UPDATE salaam_murabaha_deal SET rebate_amount = ? WHERE loan_id = ?", totalRebate, loanId);
        return retrieveDeal(loanId);
    }

    public MurabahaDealData retrieveDeal(final Long loanId) {
        context.authenticatedUser().validateHasReadPermission("MURABAHA");
        final List<MurabahaDealData> deals = jdbcTemplate.query("""
                SELECT d.*, l.product_id FROM salaam_murabaha_deal d JOIN m_loan l ON l.id = d.loan_id WHERE d.loan_id = ?
                """, (rs, i) -> {
            final BigDecimal margin = rs.getBigDecimal("margin");
            final BigDecimal price = rs.getBigDecimal("purchase_price");
            final Long capitalizedIncomeTxnId = rs.getObject("capitalized_income_txn_id") == null ? null
                    : rs.getLong("capitalized_income_txn_id");
            return new MurabahaDealData(rs.getLong("loan_id"), rs.getLong("product_id"), rs.getString("status"),
                    rs.getString("asset_description"), rs.getString("supplier_name"), rs.getString("invoice_number"), price,
                    String.valueOf(rs.getDate("purchase_date")), rs.getString("purchase_journal_entry_id"),
                    rs.getDate("sale_date") == null ? null : String.valueOf(rs.getDate("sale_date")), margin,
                    margin == null ? null : price.add(margin), capitalizedIncomeTxnId,
                    rs.getBigDecimal("rebate_amount"));
        }, loanId);
        require(!deals.isEmpty(), "error.msg.murabaha.deal.not.found", "No Murabaha purchase is recorded for loan {0}", loanId);
        return deals.get(0);
    }

    private static final String PRODUCT_SQL = """
            SELECT p.loan_product_id, lp.name, p.clearing_gl_account_id, p.cash_gl_account_id, p.sale_payment_type_id
            FROM salaam_murabaha_product p JOIN m_product_loan lp ON lp.id = p.loan_product_id
            """;

    private MurabahaProductData retrieveProduct(final Long loanProductId) {
        final List<MurabahaProductData> products = jdbcTemplate.query(PRODUCT_SQL + " WHERE p.loan_product_id = ?",
                (rs, i) -> new MurabahaProductData(rs.getLong("loan_product_id"), rs.getString("name"), rs.getLong("clearing_gl_account_id"),
                        rs.getLong("cash_gl_account_id"), rs.getLong("sale_payment_type_id")),
                loanProductId);
        require(!products.isEmpty(), "error.msg.murabaha.product.not.configured", "Loan product {0} is not a Murabaha product",
                loanProductId);
        return products.get(0);
    }

    private Map<String, Object> loadLoan(final Long loanId) {
        final List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT l.id, l.product_id, l.loan_status_id, l.approved_principal, l.currency_code,
                    COALESCE(c.office_id, g.office_id) AS office_id
                FROM m_loan l LEFT JOIN m_client c ON c.id = l.client_id LEFT JOIN m_group g ON g.id = l.group_id
                WHERE l.id = ?
                """, loanId);
        require(!rows.isEmpty(), "error.msg.murabaha.loan.not.found", "Loan {0} does not exist", loanId);
        return rows.get(0);
    }

    private CommandProcessingResult execute(final CommandWrapperBuilder builder, final JsonObject json) {
        final CommandWrapper wrapper = builder.withJson(json.toString()).build();
        return commandsSourceWritePlatformService.logCommandSource(wrapper);
    }

    private static JsonArray glLine(final Long glAccountId, final BigDecimal amount) {
        final JsonObject line = new JsonObject();
        line.addProperty("glAccountId", glAccountId);
        line.addProperty("amount", amount);
        final JsonArray lines = new JsonArray();
        lines.add(line);
        return lines;
    }

    private static void addDateAndLocale(final JsonObject json) {
        json.addProperty("dateFormat", DATE_FORMAT);
        json.addProperty("locale", LOCALE);
    }

    private static LocalDate parseDate(final String value, final String field) {
        require(StringUtils.isNotBlank(value), "error.msg.murabaha.date.required", "{0} is required (yyyy-MM-dd)", field);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new GeneralPlatformDomainRuleException("error.msg.murabaha.date.invalid", field + " must use the format yyyy-MM-dd",
                    field);
        }
    }

    private static boolean isPositive(final BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private int count(final String sql, final Object... args) {
        final Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static void require(final boolean condition, final String code, final String message, final Object... args) {
        if (!condition) {
            throw new GeneralPlatformDomainRuleException(code, MessageFormat.format(message.replace("'", "''"), args), args);
        }
    }
}
