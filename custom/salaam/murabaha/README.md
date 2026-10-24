# Salaam Murabaha custom module

Fineract custom module for Salaam Bank's Murabaha financing (POC: Motor – Murabaha, UGX).
It touches no upstream files: Gradle picks it up from `custom/salaam/murabaha/*`, and Spring
loads it through `SalaamMurabahaAutoConfiguration` (disable with `salaam.murabaha.enabled=false`).

## How a Murabaha deal maps to Fineract

| Step | What happens | Fineract mechanism |
|------|--------------|--------------------|
| Approve | Customer's financing application is approved | Standard loan approval (progressive schedule, 0% interest) |
| Purchase | Bank buys the asset from the supplier | `POST /v1/murabaha/deals/{loanId}/purchase` posts Dr Murabaha clearing / Cr Cash |
| Sale | Bank sells the asset to the customer at cost + margin | `POST /v1/murabaha/deals/{loanId}/sale` disburses the cost (payment type mapped to clearing) and adds the margin as Capitalized Income (deferred profit) |
| Profit recognition | Margin is recognised over the term | `CAPITALIZED_INCOME_AMORTIZATION` COB step |
| Early settlement rebate (ibra) | Bank waives part of the unearned margin | `POST /v1/murabaha/deals/{loanId}/rebate` posts a Capitalized Income Adjustment |
| Late payment | Charges go to charity, never to bank income | Penalty mapped to a "Late payment charges for charity" income GL |

A pre-disbursal business event listener blocks any disbursement of a Murabaha loan that has no recorded purchase.

## Database

Liquibase changelog `db/custom-changelog/0001_salaam_murabaha.xml` creates `salaam_murabaha_product`,
`salaam_murabaha_deal` and the permissions `READ_MURABAHA`, `CREATE_MURABAHAPRODUCT`, `PURCHASE_MURABAHA`,
`SELL_MURABAHA`, `REBATE_MURABAHA`.

## API

- `POST /v1/murabaha/products` registers a loan product as Murabaha: `{loanProductId, clearingGlAccountId, cashGlAccountId, salePaymentTypeId}`
- `GET /v1/murabaha/products`
- `GET /v1/murabaha/deals/{loanId}`
- `POST /v1/murabaha/deals/{loanId}/purchase`: `{assetDescription, supplierName, invoiceNumber, purchasePrice, purchaseDate}`
- `POST /v1/murabaha/deals/{loanId}/sale`: `{saleDate, margin}`
- `POST /v1/murabaha/deals/{loanId}/rebate`: `{rebateDate, amount}`

Dates use `yyyy-MM-dd`.

## Build and run

```
./gradlew :fineract-provider:bootJar :custom:salaam:murabaha:starter:jar :custom:salaam:murabaha:service:jar
java -Dloader.path=custom/salaam/murabaha/starter/build/libs,custom/salaam/murabaha/service/build/libs \
     -jar fineract-provider/build/libs/fineract-provider-*.jar
```

## End-to-end POC

`poc/murabaha_poc.py` (Python 3, standard library only) configures a fresh tenant (GLs, payment type,
product, COB step), runs a full deal (58m approved, 50m purchase, 8m margin, one repayment, 1m rebate)
and prints the ledger so you can check that the clearing account nets to zero.

```
python3 custom/salaam/murabaha/poc/murabaha_poc.py --url http://localhost:8080/fineract-provider/api/v1
```
