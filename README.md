
# DI LUCCA — Billing API

Backend microservice for the Billing bounded context of **DI LUCCA Dental Care & Technology**.

Developed as part of the Distributed Systems 2026-B project at CORHUILA.

## Project Overview

The Billing service is responsible for the financial operations of the dental care platform, including procedure pricing, invoice management, manual charges, and payment registration.

The service follows the API contracts and domain rules maintained in the official DI LUCCA documentation repository.

## Technology Stack

- Java 21
- Spring Boot 4
- Maven
- PostgreSQL
- Liquibase (managed in a separate database repository)
- JUnit 5

## Architecture

DI LUCCA uses a distributed architecture organized into bounded contexts:

- IAM
- Patients
- Appointments
- Clinical
- Billing

The Billing service owns its financial domain and uses the `billing` schema in PostgreSQL.

Authentication, authorization, and external API routing are coordinated with the platform's IAM and API Gateway components.

## Current Implementation

The local implementation includes:

- Exact monetary validation using `BigDecimal`.
- Positive procedure prices in COP.
- Procedure price validity periods.
- Detection of overlapping validity intervals.
- Required procedure catalog field validation.
- JDBC persistence and paginated queries against PostgreSQL.
- `GET /api/v1/procedure-prices` for Administrator, Dentist, and Secretary/Assistant.
- `GET /api/v1/additional-price-rules` for Administrator and Secretary/Assistant, with pagination, exact unit prices, and stable ordering.
- `POST /api/v1/procedure-prices` for Administrator, with strict JSON validation and a UUID `Idempotency-Key`.
- Atomic price version creation, closure of previous validity, and preservation of historical prices.
- Persistent idempotency scoped by actor, operation, and procedure; identical retries return the stored HTTP 201 response and Location, while changed content returns HTTP 409.
- At least seven days of idempotency retention, committed in the same transaction as the price write.
- RS256 JWT signature verification through JWKS, issuer/audience validation, required claims, and role authorization.
- HTTP, cryptographic, PostgreSQL concurrency, and rollback tests.

Successful price creation returns HTTP 201 with a relative `Location` identifying the created price. The administrator identity is derived from the verified JWT subject, never from the request body or identity headers.

JWT verification requires the actual IAM deployment values for `BILLING_IAM_JWKS_URI`, `IAM_JWT_ISSUER`, and `IAM_JWT_AUDIENCE`. Do not use test issuers, keys, or addresses in a deployed environment.

The POST requires `billing.idempotency_records` from the `billing-005-create-idempotency-records` changeset in `dlc-billing-db`. That migration remains on its local development branch and must be reviewed and promoted before deploying the POST to another environment.

Additional rule listing requires `billing.additional_price_rules` from `billing-006-create-additional-price-rules`, currently on the local `feat/billing-additional-price-rules-schema` database branch. The table enforces positive COP amounts, allowed item types, unique versions, and nonoverlapping active validity intervals. The additional-rule POST is not implemented yet.

### Pending Integration

- IAM session revocation and eligibility checks through the private session-validation contract. This requires a separately provisioned service credential with `identity:validate`; staff JWTs cannot substitute for it.
- Remaining Billing REST endpoints defined in the OpenAPI contract.
- Invoice and payment persistence.
- Operational API Gateway routing and end-to-end integration with the shell and Billing portal.
- Integrated QA validation before release promotion.

These capabilities must follow the official contracts and must not introduce undocumented endpoints or events.

## Related Repositories

- [DI LUCCA Documentation](https://github.com/code-corhuila/dlc-docs)
- [Billing Database](https://github.com/code-corhuila/dlc-billing-db)
- [Billing Portal](https://github.com/code-corhuila/dlc-billing-portal)
- [API Gateway](https://github.com/code-corhuila/dlc-api-gateway)
- [Frontend Container](https://github.com/code-corhuila/dlc-front)

## Build and Test

Requirements:

- JDK 21
- Maven

Run automated tests:

```bash
mvn clean test
```

Build the application:

```bash
mvn clean package
```

The generated application artifact is located in the `target/` directory.

PostgreSQL tests are opt-in: supply `BILLING_TEST_JDBC_URL`, `BILLING_TEST_DB_USER`, and `BILLING_TEST_DB_PASSWORD` for an isolated migrated test database before running the suite. Without the JDBC URL, database tests are skipped; a successful unit-test run alone does not verify persistence. Keep credentials out of logs, source files, and commits. Always clean compiled output when switching branches.

## Branching and Contributions

The project uses three permanent branches:

- `develop` — development integration.
- `qa` — quality assurance.
- `main` — release branch.

Changes are developed in short-lived branches, such as:

- `feat/`
- `fix/`
- `chore/`
- `docs/`

All changes must be submitted through Pull Requests.

Development practices:

- Follow Conventional Commits.
- Use TDD (RED → GREEN → REFACTOR) for behavioral changes.
- Keep Pull Requests within 400 changed lines.
- Validate tests and builds before requesting integration.
- Do not introduce undocumented API endpoints or domain events.

Branch promotion and review requirements must follow the current DI LUCCA governance documentation.

## Documentation

Official project documentation:

https://github.com/code-corhuila/dlc-docs

API contracts:

`07-api/contracts/openapi/billing-service.yaml`

Billing service documentation:

`09-microservices/services/06-billing-service/`

Database migrations are maintained separately in `dlc-billing-db`.

## Project

**DI LUCCA Dental Care & Technology**

CORHUILA — Systems Engineering
Distributed Systems — 2026-B
