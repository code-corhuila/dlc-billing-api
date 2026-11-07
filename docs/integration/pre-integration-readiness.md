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

## Final local verification

- Full isolated Maven verify: 223 tests, zero failures/errors/skips; fresh
  PostgreSQL migrations, repeat migration verification and actual RabbitMQ.
  Evidence: `target/billing-offline-final-verify.log`.
- Portal: 609 passing tests, official contract projection check and Angular
  compilation. Evidence: `target/portal-offline-full-tests.log`,
  `target/portal-offline-contract.log`, `target/portal-offline-build.log`.
- Local artifacts: `target/dlc-billing-api-0.1.0-SNAPSHOT.jar` and Portal release
  `/portals/billing/2.0.4-local-20261107/entry.js`. No image or release was published.
- All 19 owned contractual operations now have implementations; conditional
  Dentist financial access and cross-owner acceptance remain deferred above.
- Issuance rejects unresolved catalog proposals even for historical completed
  encounters. Valid justified historical manual prices retain their original
  care-time authority and cannot be replaced through administrative correction.
  Forward migration 028 adds that guard without rewriting migration 026.
- Changes are local commits in increments below 400 changed lines. Earlier
  clinical/manual owner-acceptance scaffolding remains uncommitted and unexecuted;
  it is not part of this local acceptance evidence.
