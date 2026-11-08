# Billing API contract audit - 2026-11-07

Scope: local API HEAD ac288ee on feat/billing-clinical-ingestion-model, including
untracked work; official dlc-docs checkout 31fb316; Billing DB 3c8e4a3. This is
a source/contract/evidence audit, not a fresh certification of a deployed stage.
No official contract, business implementation, credentials or production data changed.

## Findings by delivery priority

| Priority | Finding | Evidence and consequence |
| --- | --- | --- |
| P0 | Current working tree cannot compile tests | `mvn -q -DskipTests test-compile` failed: untracked ClinicalEventDecoderTest references missing ClinicalEventDecoder and ClinicalFact. This interrupted TDD RED work is not a regression proven in committed HEAD, but blocks an ordinary build of the present workspace. |
| P1 | Clinical ingestion is not implemented | No production inbox, consumer or source reconciliation exists for ProcedureCompleted/ClinicalCareCompleted/ConsultationRecorded. No production BillableItemsCreated v2 or EncounterChargesCompleted emission exists. ADR-015 cannot complete through the API alone. |
| P1 | Manual charge commands/reads are absent | POST /manual-charges and GET /manual-charges/{id} lack controllers and financial persistence. Admission/freeze infrastructure exists but does not constitute the manual money transaction, source validation, authorization or ManualChargeRecorded event. |
| P1 | Administrative price correction is absent | POST /billable-items/{id}/approvals is official and has no implementation. It is a correction of unresolved/unissued pricing, not a second approval of valid manual care prices. |
| P1 | Conditional Dentist financial access is absent | Security denies Dentist for five documented GET operations: billable items, invoice list/detail, PDF and payments. Completing these requires explicit financial permission AND current authoritative assignment, not merely adding the role to matchers. |
| P1 | Real financial tests start from synthetic clinical facts | Real IAM/Gateway/Appointments/Billing transport and financial transactions passed, but invoice fixtures seed clinical/pricing rows directly. They do not prove Clinical-to-charge-to-invoice integration. |
| P2 | Final browser/notification/stage acceptance is missing | No completed browser acceptance result was found. Worker email, operational credential issuance/renewal, stage TLS/cookies, bindings and recovery procedures remain delivery gates. |
| P2 | The general delivery report is stale | billing-delivery-gates.md still says payments/PDF/relay are absent. Later focused reports and actual source supersede those rows. |

P0 denotes a workspace verification blocker. P1 denotes missing functionality or
integration necessary for the official scope; P2 denotes outstanding acceptance
or operational evidence. These labels do not assert a discovered data breach.

## HTTP operation inventory

The public OpenAPI has 15 method/path operations. Twelve have handlers and three
are missing. Billing owns four additional private operations, all with handlers:
16/19 total (84.2%) by route presence. This is not a functional completion score;
existing handlers can still have authorization or integration gaps.

| Contract operation group | Present / specified | Qualification |
| --- | --- | --- |
| Procedure prices GET/POST | 2/2 | Real catalog integration evidence exists. |
| Additional price rules GET/POST | 2/2 | Real catalog integration evidence exists. |
| Billable-item GET and correction POST | 1/2 | Read exists; correction and conditional Dentist access missing. |
| Invoice collection GET/POST, detail GET, issuance POST | 4/4 | Financial lifecycle tested; clinical sources are seeded fixtures. |
| Current PDF GET | 1/1 | Real HTTP result and synthetic PDF visual inspection; conditional Dentist access missing. |
| Payments GET/POST | 2/2 | Locked balance, replay, partial/full states and canonical events verified. |
| Manual charges POST/GET | 0/2 | No ordinary manual charge workflow. |
| Private event-state PDF/summary GET | 2/2 | Separate RSA service trust and correlated immutable snapshots. |
| Private freeze/release POST | 2/2 | Coordinator/cryptographic HTTP tests; actual Clinical runtime round trip not established. |

Invoice.paidTotal is described but optional in OpenAPI. The public Invoice DTO
does not expose it; PDF/private summary derive it. This is a completeness improvement,
not an absent required response property.

## Evidence verified without repeating proven integration runs

- Last full isolated financial build: 163 tests, zero failures/errors/skips, seven
  PostgreSQL SQL suites and repeat migrations; runtime source ee99254.
- Later freeze increment: seven targeted tests (four coordinator, three private
  HTTP) and eight SQL suites passed. This does not replace a new full build of
  the final completed source tree.
- Real outbox publication: one passing scenario, 198.780 seconds; mandatory routing,
  broker restart, credential reread and stable event identity/body.
- Real payments/PDF: one passing scenario, 210.733 seconds; Shell/Gateway/IAM,
  Appointments, broker-down financial commit, concurrency/replay, immutable
  historical representations and official event schema validation.
- Adjacent Portal: 589 tests passed after the paid-PDF UI fix, but the two production
  edits remain uncommitted and build:portal failed because its release already
  exists. That fix is not yet a verified new deployed browser artifact.
- New audit check: current test compilation failed as described in P0. Existing
  successful integration runs were not repeated and no secrets were inspected.

## Remaining work and acceptance order

1. Complete the interrupted ingestion RED increment and restore a full green build.
2. Implement durable clinical inbox/source deduplication, encounter correlation and
   declared-source reconciliation; verify reordered/duplicate/late events and
   once-only BillableItemsCreated/EncounterChargesCompleted emission.
3. Implement atomic manual charges with verified care/source authority, protected
   reads, exact money, admission/closure race tests and ManualChargeRecorded.
4. Implement unresolved/unissued price correction without changing issued history.
5. Complete conditional Dentist financial authorization and revocation tests.
6. Run the actual Clinical-to-Billing lifecycle, then browser acceptance using a
   new immutable Portal release; prove current PDF after partial/full UI payments.
7. Validate worker notification delivery and stage provisioning/TLS/bindings,
   including recovery of exhausted deliveries, before declaring final delivery.

Automatic catalog selection still needs a documented business choice for its
reference instant; existing docs leave temporal selection for planned procedures
pending. Manual prices must remain exact regardless of that choice. Some work
requires other owners, but inbox, manual persistence and correction tests can
progress independently. Route counts cannot support an honest percentage or
calendar commitment for total remaining engineering effort.

## References

Official: ../dlc-docs/07-api/contracts/openapi/billing-service.yaml,
internal-coordination.yaml, Billing events/data model, ADR-015 and ADR-017.
Local evidence: [payments/PDF](payment-pdf-real-owners.md),
[outbox publication](outbox-publication-real-owners.md),
[private delivery](event-state-delivery.md), [freeze](encounter-freeze-http.md).
Current compile diagnostics: target/api-audit-test-compile.log (ignored local evidence).
