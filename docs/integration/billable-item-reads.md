# Billable item persistence and real reads

Local increment on 2026-11-06, following the official Billing OpenAPI, owner
data model, deterministic COP rule and ADR-015/ADR-017. The new endpoint is
`GET /api/v1/billable-items`, with page/limit and optional patientId/procedureId.
Both list rows and counts use the same bound filters, with createdAt/id ordering.
Malformed/noncanonical UUIDs and invalid pagination return safe contract errors.
Amounts/quantities remain decimal strings; protected justification, actor and
source correlation are excluded from the public projection.

Administrator and Secretary/Assistant can read the minimized financial view.
Dentist access remains denied: its separate financial permission and current
patient assignment must both be verified before permitting rows or counts.
No role-only shortcut or invented authorization endpoint was introduced.

## Database dependency

Requires DB branch `feat/billing-billable-snapshot-storage`, commit `26a03ae`:
changesets 008/009 add EncounterAccount and BillableItem storage, exact rounded
line totals, unique source/base keys and scoped SELECT/INSERT grants. Runtime
cannot UPDATE or DELETE these snapshots. Accounts retain the approved encounter
and freeze identities; no invoice is created as a placeholder. These tables do
not yet implement admission/closure transitions or Clinical event consumption.

## Validation

RED commits reproduce the missing controller/repository and missing validation
error body before implementation. The full Java/PostgreSQL run passed 110 tests,
zero skipped. After the error-body correction, all three controller tests passed
again and packaging succeeded. Both DB SQL suites and repeat migration passed.

The final image `dlc-billing-api:billable-read-test` passed the real shell/Gateway/
IAM/PostgreSQL integration scenario in 127.849 seconds. It checks exact strings,
stable pagination, single/combined filters and filtered counts, empty results,
role denials, projection keys and logout revocation. No owner service is mocked.
Stored rows are explicitly synthetic test snapshots, not proof of Clinical
event ingestion or invoice creation. Infrastructure and secrets are disposable.

Use the eight inputs in [the shell report](real-shell-local-verification.md),
with the new Billing runtime image and DB source revision, then run:
`python -m unittest discover -s tests/integration -p test_billable_items.py -v`.
Ignored evidence is in `target/billable-item-read-evidence`.
Invoice drafting, issuance, payments, PDF, care authority/event integration and
browser acceptance remain in [the delivery gates](billing-delivery-gates.md).
