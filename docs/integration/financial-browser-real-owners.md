# Real financial browser acceptance — 2026-11-07

Chromium acceptance passed in 262.249 seconds using actual Shell, Gateway, IAM,
Appointments, Billing and Portal assets on an isolated disposable Docker network.
The test is `tests/integration/test_browser_financial.py`; no HTTP responses,
sessions or owner services are stubbed. Clinical/pricing source rows are explicitly
synthetic fixtures, not proof of a Clinical producer workflow.

Verified through the rendered interface:

- Actual IAM login and session restoration across full page navigations.
- Actual draft POST 201, issuance POST 200 and issued PDF download.
- Payments 4.01 and 6.00 COP, each POST 201, with partial/paid PDF downloads.
- Owner database ends at PAID with balance 0.00.
- IAM logout POST 204; Billing subsequently rejects that session's JWT with 401.

Evidence is ignored locally under `target/billing-e2e-76c66f5aa3-browser/`:
`result.json`, `paid-invoice.png`, and `issued.pdf`, `partial.pdf`, `paid.pdf`.
The complete run is `target/browser-financial-logout-fixed.log`. Expected 401s
are anonymous initial CSRF acquisition and the deliberate revoked-session check.
No passwords, cookies or JWT values are written to this evidence.

Two real defects were reproduced and corrected:

1. Startup logs alone did not prove Appointments database readiness. Its cold
   first private read exceeded Billing's bounded timeout and returned 503. The
   fixture now waits on the existing owner's `/health/ready`; production request
   timeouts remain unchanged. The final draft POST succeeded without retry.
2. Front e6cfdf9 sent logout without a body; IAM requires a JSON object and
   returned 400. Front branch `fix/iam-logout-request-body`, GREEN ced033b,
   sends `{}` with its content type and fresh CSRF proof. All 169 Front tests,
   typecheck, lint and build passed. The strengthened browser test requires
   server revocation, not just navigation to Login.

Inputs: Billing image `dlc-billing-api:payment-pdf-test` (financial snapshot
ee99254), DB bfa93d4, Portal ff2720e/release `2.0.1-local-20261107`, Front
`dlc-front:billing-logout-local` (ced033b), IAM 5d53afa/DB 824e910, Appointments
73ed387/DB 5b09c81, Gateway `dlc-api-gateway:billing-runtime-test`, and
`mcr.microsoft.com/playwright:v1.65.0-noble`. Runtime/package Playwright versions
match. Use the existing real-owner image/source environment inputs, set
`BILLING_PORTAL_RELEASE` and the corrected Front/Portal images above, then run
`python -m unittest discover -s tests/integration -p test_browser_financial.py -v`.

This establishes local financial acceptance. The newer Clinical projection/
reconciliation code is tested separately and is not in this frozen financial
image. Clinical broker consumption, manual-charge HTTP completion, correction,
Dentist permission issuance, notifications and deployed TLS remain separate gates.
Nothing was pushed, merged or published.
