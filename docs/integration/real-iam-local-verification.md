# Real IAM / Gateway / Billing verification

Verified locally on 2026-11-06. The opt-in suite runs actual owner images,
PostgreSQL 16, Liquibase 4.31.1 and RabbitMQ 4.1. No IAM or Billing responses
are supplied by a stub. Only the Gateway publishes a random loopback port.

## Reproduce

Requires Docker, Python 3 and JDK 21 on PATH; run from the Billing API root.
Build Billing using [the runtime instructions](billing-runtime.md). Build IAM
and Gateway from their own Dockerfiles. Select these verified source revisions:

| Component | Source revision | Local image |
| --- | --- | --- |
| IAM API | `5d53afa` | `dlc-iam-api:billing-integration-5d53afa` |
| IAM DB | `824e910` | Liquibase reads a source checkout |
| Billing API | `65b865f` | `dlc-billing-api:deployment-test` |
| Billing DB | `f1bf379` | Liquibase reads a source checkout |
| Gateway | `a33575c` | `dlc-api-gateway:billing-runtime-test` |

Set `IAM_DB_SOURCE` and `BILLING_DB_SOURCE` to those local DB source directories.
Set `IAM_RUNTIME_IMAGE`, `BILLING_RUNTIME_IMAGE` and `GATEWAY_RUNTIME_IMAGE`
to the corresponding built images. These inputs contain no credentials. Run:

```sh
python -m unittest discover -s tests/integration -p test_real_iam.py -v
```

The fixture creates an isolated network/database, random credentials and three
synthetic verified staff identities. Real IAM login signs staff access tokens.
Separate ephemeral RSA trust supplies only the private-service test credential;
a static public JWKS contains no signing key. This is test infrastructure, not a
production credential issuer. Billing receives only its service-token directory.
Cleanup removes the fixture's containers, volumes, network and secret directory.

## Evidence and remaining gates

Two scenarios passed in 82.356 seconds: real login; catalog GET; both POSTs with
201 and exact monetary strings; concurrent identical creation; identical replay;
409 for changed content; version 2 and closure of the previous validity; role
denials; real CSRF/logout and subsequent 401; and 503 while IAM is stopped,
including replay. The private IAM path remains 404 through the public Gateway.
Both owners use limited runtime roles; cross-schema access is denied.

The former route 404 is resolved by forwarding the official `/api/v1` paths to
`auth-service:8080` and `billing-service:8080`, preserving the request URI.
Gateway `/health` alone never established that either owner was available.

Ignored local evidence is under `target/real-iam-integration/evidence`.
This proves the tested local HTTP lifecycle, not production readiness. Audited
staff provisioning, a platform service-credential provider/renewal, stage TLS,
browser Secure-cookie behavior, the real Portal/shell flow, SMTP delivery and
event-consumer processing remain separate gates. No production data was used.
