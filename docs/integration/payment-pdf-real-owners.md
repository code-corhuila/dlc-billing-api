# Real payments and PDF verification

Executed on 2026-11-06: one complete scenario, 210.733 seconds, passed.
Billing image payment-pdf-test contains API ee99254; payment DB 77dca49 and
real IAM/Appointments/Gateway/Shell/Portal image inputs match issuance verification.
The separate isolated build passed 163 tests with zero skips and seven SQL suites.

Run the existing owner environment inputs with BILLING_RUNTIME_IMAGE set to
dlc-billing-api:payment-pdf-test and DLC_DOCS_SOURCE set to the official checkout.
Use the requirements-events.txt Python environment and unittest discovery for
test_payment_pdf_real_owners.py. FinancialProbe is an observer of the actual
private Billing HTTP server; it does not return provider responses.

The scenario logs in through real IAM, allocates/issues a synthetic-source draft,
stops RabbitMQ, and records partial/full internal payments through Shell/Gateway.
It verifies configured methods, strict amounts, stale/overpayment/changed-payload
conflicts, competing payments, exact authorized replay, real read history/current
PDF and PaymentRecorded/InvoicePaid envelopes against official schemas.
Scoped RSA service credentials obtain historical PDFs/summaries after the later
payment; issuance still shows 10.01 and first payment still shows 6.00 balance.
Wrong invoice/event pairing is 404 and real logout revokes access.

All DBs, accounts, credentials, network and containers are disposable fixture-owned
resources. Source rows are explicitly synthetic; Clinical/manual ingestion,
worker email, browser execution and stage TLS are not established by this test.
