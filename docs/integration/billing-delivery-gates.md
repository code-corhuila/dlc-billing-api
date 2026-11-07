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
| Billable items | Owner-local snapshots and real filtered GET for Administrator/Assistant; see [read evidence](billable-item-reads.md) | Clinical ingestion, encounter transitions and separately authorized Dentist reads |
| Manual charges | Portal HTTP adapter exists; no API controller or persistence | Care authority/correlation, exact quantity/price, admission/closure serialization and outbox |
| Invoices | List/detail, draft POST and immutable issuance with real IAM/Appointments; [issuance evidence](invoice-issuance-real-owners.md) includes broker-down financial commit and canonical outbox | Clinical/pricing source workflows, outbox relay and PDF |
| Payments | Portal HTTP registration/history adapters exist; no API controller or persistence | Atomic locked balance updates, idempotency, immutable payment history and notifications |
| Integration events | InvoiceIssued v1 durable outbox and audit snapshot; official schema checked with broker stopped | Clinical inbox, outbox relay, event consumption and notification delivery |

The API implements catalog controllers, billable-item reads, invoice reads,
draft creation and versioned issuance. Draft writes require an owner-issued Appointments service
credential and fail closed if validation is unavailable. Unmatched operations
remain denied: payments and PDF still lack owner operations. Issuance stores
durable event intent; publication/worker delivery remain unverified and are not
implied by successful financial commit.
Do not resolve it by enabling a route that returns fabricated invoices or by
falling back to the standalone Portal's demonstration data.

## Implementation dependencies

Build the owner-local billable snapshot and encounter persistence before draft
invoice creation. Clinical completion/declaration and encounter admission must
follow ADR-015/ADR-017; a remote status read followed by an independent manual
charge write does not serialize against care closure. Drafts instead validate
the owner's terminal FINALIZADA fact for every stored source appointment.
Missing external authority must deny
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
