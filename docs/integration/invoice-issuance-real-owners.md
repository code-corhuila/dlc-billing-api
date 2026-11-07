# Invoice issuance real owner verification

## Result: 2026-11-06

The real runtime scenario passed in 166.738 seconds with Billing API 7a1e61a
and the rebuilt dlc-billing-api:invoice-issuance-test image. Issuance returned
200/ISSUED with fixed number/date, version 2 and unchanged 10.01 totals after
the real RabbitMQ container was stopped. Current detail and immutable audit
snapshot matched. The outbox envelope passed the official v1 JSON Schema and
format checks, with the original command key retained as causationId.

Three successful issuances produced exactly three audit rows and three events.
A justified Dentist price passed despite its retained legacy approval flag.
Concurrent identical requests returned equal 200 bodies; competing keys yielded
200/409. Incomplete encounter, stale version, changed replay, supplied total,
missing invoice, Dentist and unauthenticated requests were rejected. Logout
revoked issuance replay through real IAM. No broker response was simulated.
The complete Billing build separately passed 142 tests with no skips and all
five SQL suites. All disposable runtime resources were removed after execution.
The real list/detail/filtering/role/revocation regression also passed in
133.791 seconds with the new schema and immutable-header fixture adjustments.

Use the disposable owner runtime inputs from [draft verification](invoice-draft-real-owners.md).
Build the current Billing JAR/image after the issuance HTTP increment and use
the Billing DB branch feat/billing-invoice-issuance-storage (changesets 014/015).
Set DLC_DOCS_SOURCE to the official dlc-docs checkout. Install the isolated test
dependencies from tests/integration/requirements-events.txt; these are not
application runtime dependencies. Run that Python environment with
`-m unittest discover -s tests/integration -p test_invoice_issuances.py -v`.
The existing invoice-read scenario also verifies the new immutable-header policy
with dates established at fixture insertion, without rewriting historical rows.

## Coverage and boundaries

The scenario uses actual front shell/portal assets, Gateway, IAM, Billing,
Appointments and PostgreSQL. It first creates an actual HTTP draft after private
owner validation. It stops the real broker before attempting issuance. The event
envelope stored by Billing is checked against the exact official
InvoiceIssued.v1.schema.json, including UUID/date formats and prohibited fields.
Concurrency, exact replay, version/eligibility rejection, Assistant permission,
Dentist denial and post-logout denial are checked over the real HTTP path.

Clinical completion/declaration and pricing authority are explicitly synthetic
owner-local test facts. There is no live Clinical ingestion or manual pricing
entry claim. Appointments uses its actual private JWT verifier and repository;
IAM login/eligibility/logout are actual owner operations. The test-only ephemeral
service trust remains distinct from operational credential provisioning.

An outbox record proves durable publication intent, not RabbitMQ delivery.
Relay/consumer integration, notification decisions, event-state/current PDF,
payments, browser interaction and stage deployment are still separate gates.
All test resources and temporary secret files are removed after execution.
