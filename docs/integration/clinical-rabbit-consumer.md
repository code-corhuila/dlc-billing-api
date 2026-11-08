# Clinical broker consumer

Opt-in `deploy/compose.clinical.yml` enables the consumer with a rotating URI file
mounted read-only. Default exchange `clinical.events` and routing keys match the
actual Clinical c35952e Go publisher: ProcedureCompleted, ConsultationRecorded,
ClinicalCareCompleted. Queue `billing.clinical.v1` and its `.quarantine` are private
durable classic owner queues; this increment does not claim replicated HA.

Canonical body, strict UTF-8, messageId, type and route must agree. A successful
handler commits before ACK. Retry/quarantine transfers preserve body and event
identity, use persistent mandatory publication with confirms, and ACK the source
only afterward. A returned transfer leaves the original unacknowledged for broker
redelivery. At-least-once duplicates remain safe through the durable inbox and
financial receipts. Ten failed deliveries retain the original in quarantine;
invalid facts are quarantined immediately. Runtime scheduling separates retries
by passes and also sweeps pending prices when the broker is unavailable.

RED proved the missing consumer. GREEN: seven tests, zero failures/errors/skips,
with actual RabbitMQ (`target/clinical-consumer-green.log`). They cover byte and
identity preservation, UTF-8/metadata rejection, retry exhaustion, and an actual
mandatory return after deleting the quarantine queue, followed by recovery.
Handlers are isolated in transport tests; actual Clinical producer acceptance is
still separate. Full runtime wiring is checked in the next owner integration.
The complete isolated Maven verify also passed: 205 tests, zero failures/errors/
skips, with PostgreSQL and RabbitMQ (`target/clinical-manual-full-build.log`).

Broker permissions must allow this Billing identity to read/declare/bind its owner
queues and publish confirmed transfers to them. Quarantine retains original
Clinical envelopes, including protected fields; restrict broker access and never
dump message bodies into application logs. Recovery requires an operator-controlled
replay after fixing the cause; no automatic infinite poison-message loop is added.

Acknowledgement/confirm behavior follows the primary [RabbitMQ guide](https://www.rabbitmq.com/docs/confirms).
Application counters are explicit and do not depend on broker-specific quorum
delivery-limit headers or default policies.
