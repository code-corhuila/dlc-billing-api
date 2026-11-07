# RabbitMQ outbox publisher

The opt-in scheduler uses BILLING_OUTBOX_RELAY_ENABLED,
BILLING_RABBITMQ_URI_FILE, BILLING_RABBITMQ_EXCHANGE,
BILLING_RABBITMQ_LOCAL_DEVELOPMENT, BILLING_OUTBOX_INTERVAL and
BILLING_OUTBOX_BATCH_SIZE. compose.rabbitmq.yml mounts a private
broker URI directory read-only. Secrets never belong in Git or diagnostic logs.
The file is reread per attempt; a missing/rotated credential keeps intent pending.
The default is disabled until credentials and consumer bindings are provisioned.

The publisher declares its durable topic exchange only, default billing.events
as proposed by the owner contract, and uses the exact event name as routing key.
Consumers own queues/bindings. Platform registration/permissions remain stage
prerequisites; a local test declaration is not evidence of deployed topology.
Messages use persistent delivery, JSON UTF-8, eventId as messageId and the
canonical event name as type. The stored envelope is sent unchanged.

Publication requires publisher confirmation and absence of a mandatory return.
RabbitMQ sends a return before confirming an unroutable mandatory message; an
ACK alone does not establish routing. See the official
[confirmation semantics](https://www.rabbitmq.com/docs/confirms).
Consumers still need durable inbox deduplication after ambiguous publish/DB
outcomes; broker acceptance does not prove worker processing or email delivery.

AMQPS uses platform certificate trust and hostname verification. Plain AMQP
requires explicit development enablement. Connection, handshake, channel RPC
and confirmation waits are bounded; client automatic recovery is disabled so
the durable relay controls retries. Transport/provider failures lose their
causes before crossing the publisher boundary. Schedule failures log no details.
The client is explicitly pinned to 5.33.0, overriding the older parent BOM
version because of the official
[TLS advisory](https://github.com/rabbitmq/rabbitmq-java-client/security/advisories/GHSA-5m9f-rphj-c435).

The isolated full build passed 153 tests with no failures/errors/skips, including
two actual RabbitMQ tests (durable routed message/properties and mandatory
unroutable rejection), and all six PostgreSQL SQL suites. Run
`python tests/integration/verify_runtime_build.py` with BILLING_DB_SOURCE set.
It uses the local Maven dependency cache, copies sources into an isolated
container workspace and copies out the successful JAR/reports. This avoids
sharing compiled classes with the IDE. The earlier shared-output run encountered
a transient missing project class; no JWT/security behavior was changed.
