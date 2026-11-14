# Changelog

All notable changes of `dlc-billing-api` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/). A release is tagged `v<version>` once it is merged into `main`.

## [Unreleased]

## [3.0.0] - 2026-11-14

First release of `dlc-billing-api`.

### Added

- Bootstrap Spring Boot application.
- Validate exact procedure price amounts.
- Validate procedure price validity periods.
- Validate procedure price catalog entries.
- Implement procedure price JDBC persistence.
- Add procedure price listing REST controller.
- Implement paginated procedure price JDBC query.
- Enforce JWT role authorization for procedure prices.
- Configure IAM JWT decoder with JWKS validation.
- Validate required JWT identity claims.
- Authorize price creation and validate idempotency header.
- Persist atomic procedure price version transitions.
- Validate price creation HTTP payloads.
- Create price versions with transactional idempotency.
- List additional price rules with role authorization.
- Create idempotent additional price rule versions.
- Add private IAM session validation client.
- Enforce private IAM session eligibility.
- Expose scoped billable item reads from immutable snapshots.
- Serve persisted invoice lists and details.
- Allocate invoice drafts atomically with persistent replay.
- Validate finalized appointments through owner contract.
- Expose atomic invoice drafts with owner validation.
- Issue invoices with atomic history and outbox intent.
- Expose versioned invoice issuance over HTTP.
- Relay durable outbox intent with bounded retries.
- Publish durable events with confirmed mandatory routing.
- Record atomic internal payments and event-state receipts.
- Expose validated payment registration and history.
- Render protected current invoice PDF on demand.
- Authorize service-only event-state PDF and summary.
- Coordinate durable encounter freeze and admission.
- Expose scoped Clinical encounter freeze and release.
- Validate and minimize canonical clinical ingestion facts.
- Persist minimized clinical receipts before acknowledgment.
- Project reordered clinical sources atomically with inbox.
- Reconcile declared clinical charges at completedAt prices.
- Read current encounter and assignment authority safely.
- Validate exact manual charge input without catalog substitution.
- Record exact manual charges atomically under encounter gate.
- Expose authorized exact manual charge creation.
- Protect manual charge reads with live source assignment.
- Orchestrate clinical facts and revisit pending declarations.
- Consume clinical facts with confirmed retries and durable quarantine.
- Validate administrative price correction requests.
- Correct unresolved billable prices with atomic audit and replay.
- Enforce exact first payment minimum under invoice lock.
- List encounters waiting for a price.
- Obtain service tokens from IAM by client credentials (ADR-020).

### Fixed

- Register JDBC query repository and restore application context.
- Reject pagination offset overflow with HTTP 400.
- Align procedure prices route with API contract.
- Compare price validity at PostgreSQL precision.
- Bound IAM validation and reject duplicate fields.
- Accept IAM empty permission claims and retain role checks.
- Retain frozen encounter after committed clinical declaration.
- Await actual Appointments database readiness before owner tests.
- Retain catalog proposals until administrative correction resolves pricing.
- Protect pending catalog issuance and historical manual care prices.
- Reject draft charges whose encounter is not completed.
