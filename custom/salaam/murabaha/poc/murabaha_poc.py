#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""
Salaam Bank Motor - Murabaha (UGX) proof of concept.

Sets up GL accounts and the Motor - Murabaha loan product, then runs one deal end to end:
the bank buys a car for UGX 50m, sells it to the customer for UGX 58m over 24 months,
recognises the margin daily, takes one instalment and gives an early settlement rebate.
It also checks that a Murabaha loan cannot be disbursed before the purchase is recorded.

Usage: python3 murabaha_poc.py [--url http://localhost:8080/fineract-provider/api/v1]
Needs a Fineract instance with the custom/salaam/murabaha modules on the classpath.
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request

DATE_FORMAT = "yyyy-MM-dd"
LOCALE = "en"
DEAL_DATE = "2026-10-01"
COST = 50_000_000
MARGIN = 8_000_000
TERM_MONTHS = 24


class FineractError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


class Fineract:
    def __init__(self, url, user, password, tenant):
        self.url = url.rstrip("/")
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.headers = {
            "Authorization": f"Basic {token}",
            "Fineract-Platform-TenantId": tenant,
            "Content-Type": "application/json",
        }

    def call(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data=data, method=method, headers=self.headers)
        try:
            with urllib.request.urlopen(request) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raise FineractError(e.code, e.read().decode()) from None

    def get(self, path):
        return self.call("GET", path)

    def post(self, path, body):
        return self.call("POST", path, body)

    def put(self, path, body):
        return self.call("PUT", path, body)


def dated(body, **dates):
    body.update(dates)
    body.update({"dateFormat": DATE_FORMAT, "locale": LOCALE})
    return body


def step(title):
    print(f"\n== {title}")


def gl_account(api, code, name, account_type):
    existing = [a for a in api.get("/glaccounts") if a["glCode"] == code]
    if existing:
        return existing[0]["id"]
    return api.post("/glaccounts", {"name": name, "glCode": code, "type": account_type, "usage": 1,
                                    "manualEntriesAllowed": True, "description": name})["resourceId"]


def set_business_date(api, date):
    api.post("/businessdate", dated({"type": "BUSINESS_DATE"}, date=date))


def setup(api):
    step("Configuration: UGX currency and business date")
    currencies = [c["code"] for c in api.get("/currencies")["selectedCurrencyOptions"]]
    api.put("/currencies", {"currencies": sorted(set(currencies + ["UGX", "USD"]))})
    api.put("/configurations/name/enable-business-date", {"enabled": True})
    set_business_date(api, DEAL_DATE)

    step("Close of business: add daily capitalized income (margin) recognition")
    steps = [s["stepName"] for s in api.get("/jobs/LOAN_CLOSE_OF_BUSINESS/steps")["businessSteps"]]
    if "CAPITALIZED_INCOME_AMORTIZATION" not in steps:
        steps.append("CAPITALIZED_INCOME_AMORTIZATION")
        api.put("/jobs/LOAN_CLOSE_OF_BUSINESS/steps",
                {"businessSteps": [{"stepName": name, "order": i + 1} for i, name in enumerate(steps)]})
    print("  " + ", ".join(steps))

    step("GL accounts")
    asset, liability, income, expense = 1, 2, 4, 5
    gl = {
        "cash": gl_account(api, "SAL-1010", "Cash at bank", asset),
        "receivable": gl_account(api, "SAL-1100", "Murabaha receivable", asset),
        "profit_receivable": gl_account(api, "SAL-1110", "Murabaha profit receivable", asset),
        "fee_receivable": gl_account(api, "SAL-1120", "Fees receivable", asset),
        "penalty_receivable": gl_account(api, "SAL-1130", "Late charges receivable", asset),
        "clearing": gl_account(api, "SAL-1150", "Murabaha asset clearing", asset),
        "suspense": gl_account(api, "SAL-1190", "Transfers in suspense", asset),
        "deferred_profit": gl_account(api, "SAL-2100", "Deferred Murabaha profit", liability),
        "overpayment": gl_account(api, "SAL-2300", "Customer overpayments", liability),
        "profit_income": gl_account(api, "SAL-4100", "Murabaha profit income", income),
        "charity_income": gl_account(api, "SAL-4800", "Late payment charges for charity", income),
        "other_income": gl_account(api, "SAL-4900", "Other financing income", income),
        "write_off": gl_account(api, "SAL-5100", "Financing written off", expense),
    }
    for name, gl_id in gl.items():
        print(f"  {name:18} GL {gl_id}")

    step("Payment type for the sale, mapped to the clearing account")
    existing = [t for t in api.get("/paymenttypes") if t["name"] == "Murabaha sale"]
    sale_payment_type = existing[0]["id"] if existing else api.post("/paymenttypes", {
        "name": "Murabaha sale", "description": "Disbursement of a Murabaha sale against the asset clearing account",
        "isCashPayment": False, "position": 10})["resourceId"]
    print(f"  payment type {sale_payment_type}")

    step("Loan product: Motor - Murabaha (UGX)")
    existing = [p for p in api.get("/loanproducts") if p["shortName"] == "MMUG"]
    if existing:
        product_id = existing[0]["id"]
    else:
        product_id = api.post("/loanproducts", dated({
            "name": "Motor - Murabaha (UGX)", "shortName": "MMUG",
            "description": "Salaam Motor Financing under Murabaha: cost disbursed to the dealer, margin as deferred profit",
            "currencyCode": "UGX", "digitsAfterDecimal": 0, "inMultiplesOf": 1,
            "principal": COST + MARGIN, "minPrincipal": 1_000_000, "maxPrincipal": 1_000_000_000,
            "numberOfRepayments": TERM_MONTHS, "minNumberOfRepayments": 1, "maxNumberOfRepayments": 36,
            "repaymentEvery": 1, "repaymentFrequencyType": 2,
            "interestRatePerPeriod": 0, "minInterestRatePerPeriod": 0, "maxInterestRatePerPeriod": 0,
            "interestRateFrequencyType": 3, "isLinkedToFloatingInterestRates": False,
            "amortizationType": 1, "interestType": 0, "interestCalculationPeriodType": 1,
            "allowPartialPeriodInterestCalculation": False,
            "transactionProcessingStrategyCode": "advanced-payment-allocation-strategy",
            "paymentAllocation": [{
                "transactionType": "DEFAULT", "futureInstallmentAllocationRule": "NEXT_INSTALLMENT",
                "paymentAllocationOrder": [{"paymentAllocationRule": r, "order": i + 1} for i, r in enumerate([
                    "PAST_DUE_PENALTY", "PAST_DUE_FEE", "PAST_DUE_PRINCIPAL", "PAST_DUE_INTEREST",
                    "DUE_PENALTY", "DUE_FEE", "DUE_PRINCIPAL", "DUE_INTEREST",
                    "IN_ADVANCE_PENALTY", "IN_ADVANCE_FEE", "IN_ADVANCE_PRINCIPAL", "IN_ADVANCE_INTEREST"])],
            }],
            "creditAllocation": [],
            "loanScheduleType": "PROGRESSIVE", "loanScheduleProcessingType": "HORIZONTAL",
            "daysInMonthType": 30, "daysInYearType": 360, "isInterestRecalculationEnabled": False,
            "isEqualAmortization": False, "enableDownPayment": False, "canDefineInstallmentAmount": False,
            "includeInBorrowerCycle": False, "useBorrowerCycle": False, "holdGuaranteeFunds": False,
            "accountMovesOutOfNPAOnlyOnArrearsCompletion": False, "multiDisburseLoan": False,
            "overdueDaysForNPA": 90, "repaymentStartDateType": 1, "canUseForTopup": False,
            "enableIncomeCapitalization": True, "capitalizedIncomeCalculationType": "FLAT",
            "capitalizedIncomeStrategy": "EQUAL_AMORTIZATION", "capitalizedIncomeType": "FEE",
            "deferredIncomeLiabilityAccountId": gl["deferred_profit"],
            "incomeFromCapitalizationAccountId": gl["profit_income"],
            "accountingRule": 3,
            "fundSourceAccountId": gl["cash"], "loanPortfolioAccountId": gl["receivable"],
            "paymentChannelToFundSourceMappings": [{"paymentTypeId": sale_payment_type,
                                                    "fundSourceAccountId": gl["clearing"]}],
            "transfersInSuspenseAccountId": gl["suspense"], "interestOnLoanAccountId": gl["other_income"],
            "incomeFromFeeAccountId": gl["other_income"], "incomeFromPenaltyAccountId": gl["charity_income"],
            "incomeFromRecoveryAccountId": gl["other_income"], "writeOffAccountId": gl["write_off"],
            "overpaymentLiabilityAccountId": gl["overpayment"], "receivableInterestAccountId": gl["profit_receivable"],
            "receivableFeeAccountId": gl["fee_receivable"], "receivablePenaltyAccountId": gl["penalty_receivable"],
            "goodwillCreditAccountId": gl["write_off"], "incomeFromGoodwillCreditInterestAccountId": gl["other_income"],
            "incomeFromGoodwillCreditFeesAccountId": gl["other_income"],
            "incomeFromGoodwillCreditPenaltyAccountId": gl["other_income"],
            "incomeFromChargeOffInterestAccountId": gl["other_income"], "incomeFromChargeOffFeesAccountId": gl["other_income"],
            "incomeFromChargeOffPenaltyAccountId": gl["other_income"], "chargeOffExpenseAccountId": gl["write_off"],
            "chargeOffFraudExpenseAccountId": gl["write_off"], "enableAccrualActivityPosting": False,
            "charges": [],
        }))["resourceId"]
    print(f"  loan product {product_id}")

    step("Mark the product as Murabaha")
    print(" ", api.post("/murabaha/products", {"loanProductId": product_id, "clearingGlAccountId": gl["clearing"],
                                                "cashGlAccountId": gl["cash"], "salePaymentTypeId": sale_payment_type}))
    return gl, product_id


def new_approved_loan(api, product_id, name):
    client_id = api.post("/clients", dated({"officeId": 1, "legalFormId": 1, "firstname": name, "lastname": "Customer",
                                            "active": True}, activationDate=DEAL_DATE))["clientId"]
    loan_id = api.post("/loans", dated({
        "clientId": client_id, "productId": product_id, "loanType": "individual",
        "principal": COST + MARGIN, "loanTermFrequency": TERM_MONTHS, "loanTermFrequencyType": 2,
        "numberOfRepayments": TERM_MONTHS, "repaymentEvery": 1, "repaymentFrequencyType": 2,
        "interestRatePerPeriod": 0, "amortizationType": 1, "interestType": 0, "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": "advanced-payment-allocation-strategy",
    }, submittedOnDate=DEAL_DATE, expectedDisbursementDate=DEAL_DATE))["loanId"]
    api.post(f"/loans/{loan_id}?command=approve", dated({}, approvedOnDate=DEAL_DATE, expectedDisbursementDate=DEAL_DATE))
    return loan_id


def journal(api, gl_id, loan_id=None):
    path = f"/journalentries?glAccountId={gl_id}&limit=200"
    if loan_id:
        path += f"&loanId={loan_id}"
    entries = api.get(path)["pageItems"]
    debit = sum(e["amount"] for e in entries if e["entryType"]["value"] == "DEBIT")
    credit = sum(e["amount"] for e in entries if e["entryType"]["value"] == "CREDIT")
    return debit, credit


def show_transactions(api, loan_id):
    loan = api.get(f"/loans/{loan_id}?associations=transactions,repaymentSchedule")
    for t in loan.get("transactions", []):
        if not t.get("manuallyReversed"):
            print(f"  {'-'.join(str(x) for x in t['date'])}  {t['type']['value']:40} {t['amount']:>14,.0f}")
    return loan


def run(api):
    gl, product_id = setup(api)

    step("Guard: a Murabaha loan cannot be disbursed before the purchase")
    blocked_loan = new_approved_loan(api, product_id, "Blocked")
    try:
        api.post(f"/loans/{blocked_loan}?command=disburse", dated({"transactionAmount": COST},
                                                                  actualDisbursementDate=DEAL_DATE))
        print("  FAIL: disbursement was allowed")
        return False
    except FineractError as e:
        errors = json.loads(e.body).get("errors") or [{}]
        print(f"  OK: rejected with HTTP {e.status}: {errors[0].get('defaultUserMessage')}")

    step("Deal: approve a UGX 58m Murabaha sale for 24 months")
    loan_id = new_approved_loan(api, product_id, "Motor")
    print(f"  loan {loan_id}")

    step("1. Bank buys the car from the dealer")
    print(" ", api.post(f"/murabaha/deals/{loan_id}/purchase", {
        "assetDescription": "Toyota Hilux 2024, chassis AHT1234567", "supplierName": "Kampala Motors Ltd",
        "invoiceNumber": "KM-2026-0412", "purchasePrice": COST, "purchaseDate": DEAL_DATE}))

    step("2. Bank sells the car to the customer at cost plus margin")
    deal = api.post(f"/murabaha/deals/{loan_id}/sale", {"saleDate": DEAL_DATE, "margin": MARGIN})
    print(" ", deal)

    step("3. Close of business for 1 and 2 October recognises profit daily")
    set_business_date(api, "2026-10-03")
    api.post("/jobs/LOAN_COB/inline", {"loanIds": [loan_id]})
    loan = show_transactions(api, loan_id)

    step("4. First instalment paid on 1 November")
    set_business_date(api, "2026-11-01")
    instalment = next(p["totalDueForPeriod"] for p in loan["repaymentSchedule"]["periods"] if p.get("period") == 1)
    api.post(f"/loans/{loan_id}/transactions?command=repayment", dated({"transactionAmount": instalment},
                                                                         transactionDate="2026-11-01"))
    print(f"  paid {instalment:,.0f}")

    step("5. Early settlement rebate (ibra) of UGX 1m on the unearned margin")
    print(" ", api.post(f"/murabaha/deals/{loan_id}/rebate", {"rebateDate": "2026-11-01", "amount": 1_000_000}))
    show_transactions(api, loan_id)

    step("Ledger check")
    clearing = journal(api, gl["clearing"])
    print(f"  Murabaha asset clearing (all deals): debits {clearing[0]:,.0f}, credits {clearing[1]:,.0f}"
          f" -> {'nets to zero' if clearing[0] == clearing[1] else 'NOT ZERO'}")
    cash = journal(api, gl["cash"])
    print(f"  Cash at bank: debits {cash[0]:,.0f}, credits {cash[1]:,.0f}")
    deferred = journal(api, gl["deferred_profit"], loan_id)
    print(f"  Deferred Murabaha profit (this loan): debits {deferred[0]:,.0f}, credits {deferred[1]:,.0f}")
    income = journal(api, gl["profit_income"], loan_id)
    print(f"  Murabaha profit income (this loan): credits {income[1]:,.0f}")
    summary = api.get(f"/loans/{loan_id}")["summary"]
    print(f"  Outstanding balance: {summary['totalOutstanding']:,.0f}")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8080/fineract-provider/api/v1")
    parser.add_argument("--user", default="mifos")
    parser.add_argument("--password", default="password")
    parser.add_argument("--tenant", default="default")
    args = parser.parse_args()
    api = Fineract(args.url, args.user, args.password, args.tenant)
    for _ in range(60):
        try:
            api.get("/offices")
            break
        except (urllib.error.URLError, ConnectionError):
            time.sleep(5)
    sys.exit(0 if run(api) else 1)


if __name__ == "__main__":
    main()
