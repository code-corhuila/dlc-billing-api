# Durable outbox relay

DB changesets 016/017 separate delivery metadata from immutable financial events.
Only committed PENDING rows due for retry are selected. Workers lock delivery
rows with SKIP LOCKED and process bounded batches, preserving the stored body
and eventId. Financial invoice rows are never locked by the relay.

Successful publisher completion marks PUBLISHED in the relay transaction. A
transport/confirmation failure schedules exponential backoff from five seconds
to fifteen minutes plus bounded jitter. Ten failed attempts retain FAILED work
for operator reconciliation; no automatic deletion/reopening is provided.
The remaining batch is untouched on transport failure.

Five tests passed against real PostgreSQL with billing_runtime: exact committed
body/identity, delayed retry, bounded exhaustion, competing workers and a forced
database failure after successful publication. That last case replays the same
event: consumers still need durable inbox/business-source deduplication.
These tests use a publisher double and do not claim RabbitMQ delivery. The
following publisher/integration increments must prove real broker confirms,
mandatory-return handling and recovery without changing the financial response.
