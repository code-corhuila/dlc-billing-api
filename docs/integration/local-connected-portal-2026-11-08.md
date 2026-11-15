# Connected local Billing Portal acceptance

Actual IAM, Gateway, Billing and Appointments APIs are now connected to the local
composed Billing Portal 2.1.0. This supersedes the interfaces-only limitation recorded
when the initial-payment PR was first opened. No production deployment is claimed.

Real login, authenticated reads, draft creation, issuance, payments, PDF and logout
revocation passed through the shell. A first COP 5.00 payment on COP 10.01 was rejected
with 409 `INITIAL_PAYMENT_BELOW_MINIMUM`; COP 5.01 followed by COP 5.00 succeeded.
Chromium independently exercised the financial interface and downloaded the issued,
partially paid and paid PDFs. No HTTP responses or authenticated sessions were stubbed.

The installation retains separate owner-scoped database roles, an isolated persistent
volume and renewable private-service credentials. Restart/recreation was verified with
real IAM login, Billing reads and subsequent session revocation.

After acceptance, the user requested removal of demo data. The two synthetic invoices,
four payments and their financial/source/appointment fixtures were removed. Authenticated
invoice/catalog reads now return empty data. Existing local PostgreSQL deployments,
administrator access, migrations and payment methods were preserved. Further financial
scenario tests must run only on disposable infrastructure, not this cleaned installation.

## Publication follow-up

The PriceCorrection JDBC fixture now creates an invoice as DRAFT, allocates items and
then issues it, matching the new DB integrity boundary. The payment/PDF integration
and browser fixtures use the agreed 50% first-payment minimum, retaining historical
snapshot, replay, role, concurrency and logout assertions. Pending manual-source
integration probes and their separate service scopes are included as test infrastructure.

Publication checks: Maven reported 227 tests, zero failures/errors and 83 infrastructure
skips; separately, 27 JDBC transaction tests passed without skips against disposable
PostgreSQL with the current DB migrations. Both browser scripts pass syntax checks and
the Java integration probes compile. Portal contract projections pass their checks.

The updated real-owner payment/PDF scenario also passed in 241.317 seconds on disposable
IAM, Gateway, Appointments, Billing, PostgreSQL and RabbitMQ infrastructure. It checks
the first-payment rejection, valid partial/final payments, role denial, concurrent
payment conflict, exact replay, current/historical PDFs and summaries, immutable event
schemas, commit with the broker stopped and logout revocation. This run was sequential
with respect to Maven clean, so temporary mounted service credentials were retained.
All scenario resources were cleaned up; the user-facing empty database was untouched.

Private environment files, tokens, keys, credentials and backups are excluded from Git.
Stored source facts are synthetic; this does not establish a full Clinical workflow,
Worker email delivery, production credential provisioning or production readiness.
