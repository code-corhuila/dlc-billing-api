# Local readiness before connecting owner microservices

The user deferred cross-service integration. Do not start IAM, Gateway,
Appointments, Clinical, Patients or Worker acceptance until that work resumes.
Local unit, HTTP-boundary and disposable PostgreSQL/RabbitMQ tests remain valid.
Authentication collaborators in unit tests are not final integration evidence.

## Prepared locally

- Catalog selection uses ProcedureCompleted.completedAt; care-time manual prices
  remain exact and do not require a second approval.
- Durable clinical inbox, strict event decoding, source projection, reconciliation,
  retry/quarantine transport and financial outbox are implemented.
- Manual charge creation/read, concurrency, idempotency and protected source
  navigation are implemented. Real source and current assignment checks remain
  mandatory when the owner clients are configured.
- Drafts, issuance, partial/full payments, immutable historical financial PDFs
  and event-state notification reads are implemented.
- Administrative correction now has an HTTP route, version checks, exact replay,
  restricted database capability and protected audit. Draft totals are updated;
  issued snapshots and valid manual prices remain protected.
- Catalog proposals requiring approval stay pending until correction. Missing
  source codes or catalog prices still remain unresolved without fabricated money.

## Deferred integration gates

1. Actual Clinical producer and complete manual browser workflow through owners.
2. IAM's official explicit financial permission and current assignment proof for
   Dentist financial GETs. Until established, those reads remain denied.
3. Actual Worker notification delivery using the canonical events and private
   event-state reads already implemented by Billing.
4. Target deployment, TLS, service credential provisioning, broker ACLs/topology,
   operator quarantine replay and recovery acceptance.

Never disable session validation to make local readiness look complete. Missing
owner configuration must fail closed. Supply credentials through existing
environment/secret-file interfaces; no secrets belong in versioned files or chat.
No production database was changed, and nothing was published.
