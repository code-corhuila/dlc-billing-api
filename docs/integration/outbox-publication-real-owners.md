# Real outbox publication

The runtime scenario uses Billing API 2cac4e8, DB 974ab29 (016/017), the
outbox-publication-test image and the real owner image inputs from
[issuance verification](invoice-issuance-real-owners.md). Client 5.33.0 is included
in the JAR produced by the isolated build; 153 tests and six SQL suites passed.

Use the existing IAM/Billing/Appointments/Gateway/front/portal source/image
environment inputs. Set DLC_DOCS_SOURCE to the official contracts checkout and
BILLING_PROBE_JAR to the verified Billing executable JAR; the observer extracts
its complete runtime dependencies into its temporary fixture directory. Install the existing
requirements-events.txt in an isolated Python environment, then run that Python
with `-m unittest discover -s tests/integration -p test_outbox_publication.py -v`.

The observer uses the real Java AMQP client to bind a durable test queue and
acknowledge received messages. It validates publication, not worker behavior.
The API alone declares billing.events; the observer owns its queue/binding.
Credentials are generated in memory, passed through process environment or a
temporary mounted file, and removed with all owned containers/network/volumes.
No broker/database production address or credential is accepted by the fixture.

The scenario covers mandatory no-binding retention, publication after binding,
financial commit during broker outage, publication after real broker restart,
and credential-file replacement without API restart. Received bodies must match
the stored envelopes, pass official InvoiceIssued v1 JSON Schema/format checks
and preserve messageId/type/contentType/persistent delivery. Financial replays
stay byte-identical after publication/recovery; logout still revokes access.

Executed successfully on 2026-11-06: one full scenario, 198.780 seconds. All
three events reached PUBLISHED (two or three attempts), with distinct IDs and
exact stored bodies. The initial interrupted run had no final result; this run
includes the complete artifact classpath and explicit Billing/IAM HTTP readiness.

The separate test_outbox_upgrade.py passed in 92.541 seconds from DB 1f35d18:
an existing event was scheduled once without changing its body; repeat migration
did not duplicate work. Set BILLING_BASELINE_DB_SOURCE to that archived owner
checkout and BILLING_DB_SOURCE to the new one to repeat the disposable test.

These are synthetic stored clinical/pricing facts with actual owner HTTP,
private IAM/Appointments validation and real broker transport. Clinical ingestion,
worker inbox/notification delivery, PDF, payments, browser and stage TLS/bindings
remain unverified. Broker confirmation does not establish email delivery;
ambiguous outcomes can resend the same event and require consumer deduplication.
