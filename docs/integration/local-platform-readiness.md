# Local IAM, Gateway and Billing integration

Inspected on 2026-11-05 (America/Bogota). This is a readiness investigation,
not evidence of a successful authenticated end-to-end deployment.

## Exact cause of the 404

Gateway `origin/develop` at `533d3d0` includes only `nginx/routes/_health.conf`.
`nginx/conf.d/20-server.conf` includes that directory and returns 404 for every
unmatched path. Thus `/health` returns 200 locally inside NGINX, while Auth and
Billing routes never reach an upstream. Neither IAM authentication nor Billing
validation produces those observed 404 responses. Private `/internal/v1` paths
must continue to be inaccessible through the public Gateway.

This was already verified with the official Gateway image: GET/POST catalog
paths and POST `/api/v1/auth/login` returned the Gateway `NOT_FOUND` envelope;
access logs had no upstream. These unchanged checks were not repeated.
Raw local evidence is in `target/gateway-iam-verification/` (ignored).

## Repository and deployment inventory

Remote refs were refreshed without merging or publishing branches. Older local
working copies must not be mistaken for the current upstream implementation.
Official documentation was checked against `origin/main` at `ca7ce69`: the
Gateway, Billing and internal-session OpenAPI contracts were unchanged; Auth's
new identity-email delivery requirements were included in the assessment.

| Component / revision | Existing deployment and actual listener |
| --- | --- |
| `dlc-infra` / `8c110b4` | `compose.yml`: PostgreSQL only; external `platform` network, `dlc-postgres:5432`, no published host port. Image is PostgreSQL 17.11, while Billing has been verified on 16. |
| Gateway / `533d3d0` | `deploy/compose.yml`, `deploy/Dockerfile`; container listens on 8080, host defaults to 8000; `PLATFORM_NETWORK` defaults to `platform`. No owner routes or service discovery registry exist. |
| IAM API / `1380784` | `Dockerfile`, `deploy/compose.yml`; service DNS name `auth-service`, exposed 8080, no host port, external `IAM_PLATFORM_NETWORK`. |
| IAM DB / `824e910` | `deploy/bootstrap.sql`, `deploy/compose.yml`, `deploy/run-migration.sh`; owner bootstrap and 17 Liquibase changesets; separate `iam_migrator` and `iam_runtime`. |
| Billing API / local POST parent `7a16840` | Spring Boot default HTTP port 8080; no custom `server.port`, Dockerfile or Compose manifest. Datasource and JWT configuration must be supplied externally. |
| Billing DB / local schema branch | Changesets 005 and 006 supply idempotency and additional rules. Role and grant changelogs are empty; do not deploy the API with the migration/test administrator. |
| Shell / `e6cfdf9` | `deploy/compose.yml` defaults to host 8080 and fixture deployment data; real Auth adapter now exists in `src/core/session/authAdapter.ts`, selected by `/auth-config.json`. |

No IAM or Billing runtime was running during the previously recorded container
inventory; only `dlc-billing-db-test` was active. There is no registration to fix
in a service registry: Compose DNS resolves running service aliases on a shared
network. The real IAM service and a Billing deployment must first exist there.
Stopped portal demonstration containers are not owner API deployments.

## Contract routes and proxy wiring

Authority: `dlc-docs/07-api/contracts/openapi/{api-gateway,auth-service,
billing-service,internal-coordination}.yaml` and `07-api/authentication.md`.

- Public Auth includes `/api/v1/auth/login`, `/csrf`, `/refresh`, `/logout`
  and `/jwks` under the same `/api/v1/auth` base. Current IAM controllers use
  these exact paths. Gateway must preserve the entire `/api/v1` prefix.
- Implemented Billing routes are GET/POST `/api/v1/procedure-prices` and
  GET/POST `/api/v1/additional-price-rules`. Other documented Billing operations
  remain unimplemented; routing them alone would not implement them.
- IAM session eligibility is POST `/internal/v1/sessions/validations` on the
  private owner listener. Staff access token belongs only in `accessToken` JSON;
  Authorization carries a separate service JWT with `identity:validate`.
- Shell `deploy/nginx.conf.template` replaces `/api/v1` with `GATEWAY_UPSTREAM`.
  Its mock default omits that prefix. For the real Compose Gateway the base
  must include `/api/v1` (using the actual `api-gateway:8080` service listener),
  otherwise the shell would introduce a second source of unmatched routes.
- Shell real Auth additionally needs deployment `auth-config.json` containing
  the configured issuer and audience, and a real portal registry/assets mount.
  Its fixtures and Prism profile must not be used as final integration evidence.

## Existing configuration and missing prerequisites

IAM Compose requires `IAM_STAGE`, `IAM_ENV_FILE`, `IAM_PLATFORM_NETWORK`.
The private environment file must supply the names in IAM `.env.example`:
database URL/runtime identity; RSA private/public key, key ID, issuer/audience;
trusted internal-service JWKS/issuer/audience; allowed browser origin; independent
registration, login-IP, CSRF and email-limit keys; SMTP and RabbitMQ settings.
Names and bindings may be documented; values must remain outside Git and logs.

IAM's committed `docs/integration/infra-postgres-develop-2026-11-05.md` already
records a real database connection, JWKS 200 and generic login 401. It explicitly
does not establish successful staff login, durable broker deployment or SMTP
delivery. Its temporary local credentials are not present in the inspected
working copies. No audited initial Administrator provisioning was found here.
Do not substitute demo accounts or invent an issuer of service credentials.

Billing needs `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, `BILLING_IAM_JWKS_URI`, `IAM_JWT_ISSUER` and
`IAM_JWT_AUDIENCE`, plus an owner-scoped runtime role and applied migrations.
The private-session client added in this increment accepts a credential provider;
it does not issue credentials, install a Spring bean, or change HTTP authorization.
Wiring it into request authentication and mapping unavailable IAM to a safe denial
require a separately reviewed increment. Current JWT signature/role checks alone
do not establish revocation enforcement.

## Solution order and independently verified work

1. Provision isolated development networking, per-owner database users and secrets
   using owner bootstrap/migration mechanisms. Do not reuse production databases.
2. Provide IAM runtime dependencies and audited initial staff provisioning. A
   platform issuer/provider must supply and renew separate service credentials.
3. Add Billing runtime packaging and wire private eligibility checks before
   treating JWT validation as complete session security.
4. Implement Gateway Auth/Billing routes with credential verification, removal
   of untrusted identity headers, preserved URI/query/cookies and correlation.
   An open forwarding rule alone does not satisfy the edge security contract.
5. Configure the existing real shell Auth adapter and real portal releases.
6. Verify real login, authorized reads, 201/Location, replay/conflict/concurrency,
   role denials, logout and revoked-session denial through that same Gateway.

The existing manifests can supply parts of this topology, but no complete real
platform Compose is available. Nothing in this investigation changes official
contracts, production data, or the previously passing POST implementation.
The independent private-session transport tests exercise its HTTP boundary only;
they do not claim that a live IAM deployment or successful staff login exists.

## Validation of this increment

On local branch `feat/billing-iam-session-client`, based on the verified POST
commit `7a16840`, TDD reproduced the missing client, ambiguous JSON acceptance and
an unbounded response-body wait before their fixes. The final Java 21 / PostgreSQL
16 run passed 100 tests, with zero failures, errors or skipped tests. This includes
27 private-client boundary tests and the existing catalog persistence, concurrency,
rollback, idempotency and JWT tests. No new runtime secret or dependency was added.

## Runtime packaging follow-up: 2026-11-06

Local `chore/billing-runtime-container` adds the previously missing Dockerfile
and Compose manifest. The service is `billing-service:8080` on an explicitly
selected external network, with no published deployment port. See
[Billing runtime deployment](billing-runtime.md) for required configuration and
the five passing packaging checks. A fresh JAR passed all 100 Maven tests.
Owner runtime grants, private eligibility wiring and Gateway routes remain pending;
this packaging increment does not establish a complete live integration.

## Runtime permissions and session enforcement follow-up: 2026-11-06

Billing DB branch `feat/billing-runtime-permissions` adds bootstrap identities and
changeset 007 with catalog SELECT/INSERT, validity/audit-column UPDATE and immutable
idempotency responses. Seven changesets migrated successfully as the limited
migrator on an isolated PostgreSQL 16 database; writes/rollback, denied DDL,
cross-schema access and historical rewrites were checked. Existing checksums validate.

Billing now runs private IAM eligibility checks after JWT verification on each
authenticated request. Inactivity returns 401; failed validation returns 503 before
business execution, including replayed writes. Credentials are reread from a
read-only mounted directory and must be provisioned/renewed externally. The updated
JAR passed 105 Java tests and all five real-container packaging checks. No live
IAM login or complete end-to-end success is asserted by these boundary tests.

The real IAM issuer emits an empty `permissions` array; Billing now accepts that
required string-array claim while still requiring nonempty staff roles and all
signature/issuer/audience/identity checks. A signed-token regression test reproduced
the former rejection. Final Maven verification passed 107 tests with no skips.

Gateway `origin/develop` advanced to `fdbed71` with the previously missing owner
routes and the Annex F presence-only edge controls (now reflected in official docs
at `6953a34`). Local `feat/billing-auth-owner-routing` replaces the provisional
`billing-api` target with the actual `billing-service:8080` and permits the approved
public email-verification request. Five tests passed against the real Billing
image, not a substitute service; protected requests reached its JWT rejection,
private validation stayed inaccessible and a stopped owner returned 503. Public
IAM operations returned 503 because no real IAM was running. NGINX/Compose validate.

## Real owner lifecycle follow-up: 2026-11-06

The subsequent isolated [real IAM verification](real-iam-local-verification.md)
passed login, authenticated catalog writes, concurrency, persistent replay,
versioning, role denials and logout revocation through the actual Gateway.
It uses real owner runtimes and disposable test trust/identities. The report
records reproducible inputs and the remaining browser/stage delivery gates.
