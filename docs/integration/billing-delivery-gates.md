# Billing delivery gates

Repository inspection on 2026-11-06. A working catalog integration does not
establish delivery of the full Billing service. Official requirements remain in
`dlc-docs/07-api/contracts/openapi/billing-service.yaml` and the Billing owner
data model; this report changes neither contract nor business authorization.

| Area | Current local evidence | Remaining delivery work |
| --- | --- | --- |
| Procedure prices | Real GET/POST, exact money, versions, concurrency and persistent replay | Browser creation/reload acceptance and stage promotion |
| Additional price rules | Real GET/POST, exact money, versioned persistence and replay | Browser creation/reload acceptance and stage promotion |
| IAM | Real login, private eligibility, CSRF, refresh, logout and revoked-token rejection | Operational staff provisioning and service-credential issuance/renewal |
| Shell/Gateway | Real assets and authenticated HTTP forwarding; `/api/v1` deployment prefix established | Browser session and Secure-cookie acceptance, stage TLS |
| Billable items | Contract exists; no API controller or persistence in this branch | Immutable source snapshots, deduplication, authorized reads and Clinical reconciliation |
| Manual charges | Portal HTTP adapter exists; no API controller or persistence | Care authority/correlation, exact quantity/price, admission/closure serialization and outbox |
| Invoices | Portal HTTP list/detail/issuance/PDF adapters exist; no API controller or persistence | Draft creation from owned UNBILLED sources, administrative finalization, immutable issuance and PDF |
| Payments | Portal HTTP registration/history adapters exist; no API controller or persistence | Atomic locked balance updates, idempotency, immutable payment history and notifications |
| Integration events | Real broker starts in isolated tests | Owner inbox/outbox, event publication/consumption and notification delivery |

The API source currently contains only `ProcedurePriceController` and
`AdditionalPriceRuleController`. Its security configuration denies unmatched
requests. Therefore an invoice failure after fixing forwarding is not evidence
of another Gateway routing defect: that owner operation is still missing.
Do not resolve it by enabling a route that returns fabricated invoices or by
falling back to the standalone Portal's demonstration data.

## Implementation dependencies

Build the owner-local billable snapshot and encounter persistence before draft
invoice creation. Clinical completion/declaration and encounter admission must
follow ADR-015/ADR-017; a remote status read followed by an independent write
does not serialize against care closure. Missing external authority must deny
the dependent operation, while local persistence and transaction tests proceed.

Invoice persistence/read work can then progress using explicitly synthetic
database test fixtures. Such fixtures are test evidence, not a real clinical
event producer or an integrated invoice-creation claim. Invoice creation must
select actual owned unbilled sources and calculate totals on the server.
Payments require that financial lifecycle; PDF/email depend on immutable
issued snapshots and payment history. No placeholder invoice is permitted.

Keep behavioral increments in separate local branches within 400 changed
lines, preserve RED/GREEN history and verify against PostgreSQL. Existing
successful catalog checks need repeating only when their implementation or
integration boundary changes. No push, merge or PR is authorized by this report.
