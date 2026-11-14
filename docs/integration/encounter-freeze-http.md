# Private Clinical freeze/release

The two official ADR-017 POST routes are now connected to the PostgreSQL gate.
They require separate RS256 service trust, configured Clinical subject and
billing:encounter-freeze:write; Worker/staff identities cannot invoke them. Bodies,
keys and identifiers are strict. Settlements contain source IDs/counts only.

Idempotency binds service/key/resource to operationId. Repeating a valid command
reads current settlement rather than caching a stale pendingWrites count. Released
operations replay their saved release without reopening a newer freeze. No time
expiry releases a gate. Owner declaration state/committed freeze rejects release.

RED: missing command adapter. GREEN: seven tests (four real PostgreSQL coordinator
and three cryptographic HTTP authorization tests), plus all eight SQL suites and
repeat migration. Clinical runtime producer/inbox and manual financial writes are
not implied by this command verification. Operational service credential issuance
and subject configuration are still deployment inputs, never versioned secrets.
