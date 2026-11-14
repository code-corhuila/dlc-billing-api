# Billing runtime deployment

The Dockerfile packages the Maven-verified executable JAR, runs as UID/GID 10001
and listens on the existing Spring Boot port 8080. The build context permits only
the Dockerfile and that artifact; source trees, local environments and credentials
are excluded. No new API or public health endpoint is introduced.

## Build and select the deployment

Run `mvn clean verify` with the documented isolated PostgreSQL test variables,
then `docker build -t dlc-billing-api:develop .`. Rebuild the JAR whenever source
changes; the Dockerfile does not compile or validate a pre-existing artifact.

Compose follows the IAM deployment pattern. Set these deployment selectors in
the invoking shell, not in the application environment file:

- `BILLING_STAGE`: develop, qa or main, matching the reviewed artifact.
- `BILLING_ENV_FILE`: absolute path to the stage's private runtime environment.
- `BILLING_PLATFORM_NETWORK`: existing external network for that same stage.

The environment file supplies the six existing application variables listed in
`.env.example`, with an owner-scoped database runtime identity. Credentials,
issuer and audience must come from the selected deployment. Never use the
test database administrator or this test suite's issuer/audience in a deployment.
The IAM JWKS path is `/api/v1/auth/jwks` on the real IAM listener.

From the repository root, run:

```sh
docker compose -f deploy/compose.yml config --quiet
docker compose -f deploy/compose.yml up -d --build
```

The service is reachable as `billing-service:8080` only by containers on the
selected network; Compose publishes no host port and embeds no database or IAM.
Configure the real Gateway route to preserve `/api/v1`, query parameters and
the trusted authentication boundary. Merely starting Billing does not install
the missing Gateway routes or complete revocation enforcement.

## Isolated packaging verification

With Docker, Python 3 and the existing disposable `dlc-billing-test-net` database,
set `BILLING_TEST_JDBC_URL`, `BILLING_TEST_DB_USER`, `BILLING_TEST_DB_PASSWORD` only
in the test process environment. Run `python -m unittest discover -s tests/deployment -v`
after generating the fresh JAR. The suite builds an image, validates Compose and
starts temporary Billing containers on random loopback ports. It stops/removes
only its own containers and HTTP test network, retaining the built image. The
database remains on its internal network, without a published port; each test
API temporarily joins both networks to permit loopback HTTP verification.

The packaging suite uses explicit test-only issuer/audience values, sends no
valid staff token and starts no fake IAM server. It verifies rejection of missing,
forged-header and malformed-token authentication, and startup refusal without
required IAM configuration. It does not establish database privileges, live JWKS
verification, successful login, session revocation or an authenticated end-to-end
write. Those remain the integration gates in [the readiness report](local-platform-readiness.md).

Local verification on 2026-11-06: Maven `verify` passed 100 tests with PostgreSQL
16, with zero skipped tests, and generated the executable JAR used by the image.
All five deployment tests passed, including twelve HTTP authentication-denial
cases. No Gateway/IAM end-to-end success is inferred from those results.
