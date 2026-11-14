
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

The following domain validations are implemented:

- Exact monetary validation using `BigDecimal`.
- Positive procedure prices in COP.
- Procedure price validity periods.
- Detection of overlapping validity intervals.
- Required procedure catalog field validation.

The initial domain implementation includes 20 automated tests.

### Pending Integration

- PostgreSQL repository integration.
- Procedure price version persistence.
- REST endpoints defined in the Billing OpenAPI contract.
- Authorization enforcement.
- Invoice and payment persistence.
- Integration testing with the distributed platform.

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
mvn test
```

Build the application:

```bash
mvn clean package
```

The generated application artifact is located in the `target/` directory.

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
