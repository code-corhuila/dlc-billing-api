# Administrative price correction

POST `/api/v1/billable-items/{id}/approvals` implements the existing contract.
Only an Administrator with a live IAM session may supply a positive exact COP
unitPrice, mandatory reason and expectedVersion. Actor identity is server-derived.
Ordinary justified manual care prices do not require this operation.

Billing DB migration 026 adds a restricted SECURITY DEFINER capability with a
fixed search path; runtime receives EXECUTE, not direct price/audit write grants.
The function locks the encounter, invoice and item, checks the version and pending
approval, refuses manual charges and issued invoices, records previous price,
actor, reason and version, and clears the pending flag. Allocated DRAFT totals and
balance are recomputed atomically. Existing immutable snapshots remain protected.

Correction and original response replay share one transaction. A failed receipt
write rolls back both correction and audit. Reused keys with different requests
conflict; concurrent distinct keys cannot correct the same version twice. The
public financial response excludes the protected audit reason and identity.

RED input, transaction and HTTP evidence precedes implementation. GREEN isolated
full verify passed 220 tests with zero failures/errors/skips using PostgreSQL and
RabbitMQ, with no other microservices running for the test. Evidence:
`target/price-correction-full-verify.log`. Real IAM/Gateway acceptance remains
deferred; local HTTP tests isolate authentication collaborators.
