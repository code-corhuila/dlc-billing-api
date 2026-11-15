# Billing delivery audit — 2026-11-07

> Snapshot before the later administrative-correction increments. For subsequent
> local readiness and deferred integration scope, see
> [pre-integration readiness](pre-integration-readiness.md).

This audit supersedes the completion assessment in the earlier API audit and
delivery-gates documents. It assesses local evidence, not deployed production.
API baseline: b9fffe6. Official contracts: dlc-docs 31fb316. No tests were rerun
for this audit; existing logs, source mappings and contract requirements were read.

## Completion calculation

There are 18 implemented operations out of 19 owned contractual operations
(15 public and 4 private): 94.7% route coverage, 5.3% absent. This measures route
presence only, not authorization completeness or delivery acceptance.
The missing operation is POST /api/v1/billable-items/{id}/approvals.

For delivery readiness, the following 14 acceptance areas have equal weight:
1 = demonstrated locally, 0.5 = implemented but acceptance incomplete,
0 = required acceptance not established. Scores are not estimates of engineering
hours, and equal weights do not imply equal implementation effort.

| Acceptance area | Score | Evidence or remaining requirement |
| --- | ---: | --- |
| Catalog prices and rules | 1 | HTTP, exact money, versioning and concurrency tests |
| IAM session security | 1 | Real login, logout and revoked-session denial |
| Gateway and Shell financial routing | 1 | Actual owner stack browser acceptance |
| Invoice draft and issuance | 1 | Real owner reads, HTTP and browser acceptance |
| Payments and balances | 1 | Real partial/full payments; final PAID and zero balance |
| Public/private financial PDFs | 1 | Event-state integration and downloaded browser PDFs |
| Financial outbox relay | 1 | Real broker outage, recovery and credential rotation proof |
| Financial Portal browser journey | 1 | Frozen financial release passed actual Chromium journey |
| Clinical ingestion and reconciliation | 0.5 | PostgreSQL/Rabbit tests pass; enabled runtime and actual Clinical producer workflow pending |
| Manual charges and Portal context | 0.5 | API/database and Portal tests pass; actual owner HTTP/browser acceptance pending |
| Administrative price correction | 0 | Contractual approval operation absent |
| Conditional Dentist financial access | 0 | Financial permission issuance and assignment-based access not completed |
| Notification Worker delivery | 0 | Actual consumer-to-notification delivery not established |
| Deployment and operational acceptance | 0 | Target environment, TLS, broker provisioning and operational recovery acceptance pending |

Total: 9/14 = 64.3% locally demonstrated readiness; 5/14 = 35.7% remaining.
Report this as approximately 65% complete / 35% remaining, not as a schedule.
A zero means the acceptance gate remains open; supporting code may already exist.

## Evidence and limits

- Full isolated API verify: 205 tests, zero failures/errors/skips, actual
  PostgreSQL and RabbitMQ. Log: target/clinical-manual-full-build.log.
- Portal: 599 tests passed, contract check and build passed. Logs:
  target/portal-manual-context-full-tests.log,
  target/portal-manual-context-contract.log,
  target/portal-manual-context-build.log.
- Financial browser acceptance: target/browser-financial-logout-fixed.log and
  target/billing-e2e-76c66f5aa3-browser/result.json. Actual IAM, Gateway,
  Appointments, Billing, Shell and Portal. Clinical source rows were synthetic
  fixtures; this does not prove the actual Clinical producer workflow.
- That browser run used financial API snapshot ee99254, not the newer clinical/
  manual image. The new image being built successfully does not close acceptance.
- Catalog version selection at completedAt is implemented. Manual prices retain
  their exact values; this business decision is no longer pending.
- Five financial GET operations still deny Dentist access. The contract requires
  an explicit financial permission plus current assignment; role alone is
  insufficient. The inspected IAM issuer emits an empty permissions list.
- Portal manual-context GREEN source is tested and built locally but remains
  uncommitted on a branch whose HEAD is the RED test commit. The new Portal image
  was not built. Automatic approval review failed due to a usage limit; the
  requested commit/build command was not executed. This was not a safety denial.
- New clinical/manual integration scaffolding is uncommitted and has not run;
  it contributes no acceptance credit.

## Recommended completion order

1. Run the new clinical/manual API and Portal against real IAM, Gateway,
   Appointments and Rabbit; then prove the actual Clinical producer workflow.
2. Implement administrative correction with TDD, audit history, concurrency and
   issued-document immutability.
3. Establish the official IAM financial permission and Dentist assignment checks;
   do not invent a permission name or weaken fail-closed authorization.
4. Verify actual notification Worker processing and private event-state reads.
5. Complete target deployment, TLS, broker permissions, quarantine recovery and
   regression acceptance with the exact deliverable versions.

No production database changes, publication, push, merge or PR were performed
for this audit. Earlier authorization and environment tests are not claims that
the current external environment is running now.
