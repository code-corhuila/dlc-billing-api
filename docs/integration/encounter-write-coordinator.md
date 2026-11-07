# Durable encounter write coordinator

JdbcEncounterWriteCoordinator serializes admission/freeze on one gate row and
returns one-statement settlement counts/source IDs. Empty encounters do not create
financial accounts. Unknown ADMITTED outcomes remain pending until an explicit
owner-proven resolution; elapsed time has no effect. Released operation IDs replay
the saved release even after another freeze, without releasing the newer one.
Committed declarations cannot be released.

RED: missing coordinator. GREEN: three real PostgreSQL tests using runtime,
including concurrent admission/freeze, explicit pending resolution, empty encounter,
release/replay and immutable committed freeze; all eight SQL suites also passed.
This is the local coordination core. Manual HTTP/source validation and actual
Clinical producer/inbox wiring are separate increments.
