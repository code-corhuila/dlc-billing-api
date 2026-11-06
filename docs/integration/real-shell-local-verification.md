# Real shell deployment verification

Verified locally on 2026-11-06 after [the real owner lifecycle](real-iam-local-verification.md).
An additional isolated scenario passed in 168.336 seconds using the actual
shell, IAM Portal and Billing Portal images with real IAM, Gateway and Billing.

## Inputs and routing

Use the five environment inputs documented in the owner report. Additionally set:

| Environment input | Verified image | Source |
| --- | --- | --- |
| `FRONT_RUNTIME_IMAGE` | `dlc-front:billing-integration-e6cfdf9` | `dlc-front` `e6cfdf9` |
| `IAM_PORTAL_IMAGE` | `dlc-iam-portal:billing-integration` | `dlc-iam-portal` `6ab34b2` |
| `BILLING_PORTAL_IMAGE` | `dlc-billing-portal:billing-integration-cba3c26` | `dlc-billing-portal` `cba3c26` |

Each image was built successfully using its repository's existing Dockerfile.
Run `python -m unittest discover -s tests/integration -p test_real_shell.py -v`.
The fixture mounts only the real Auth configuration and a registry referencing
the two actual portals. Other domain portals are outside this verification.

The shell proxy replaces `/api/v1`; therefore its existing `GATEWAY_UPSTREAM`
must be `http://api-gateway:8080/api/v1` on this isolated network. Omitting the
suffix sends `/auth/...` or `/procedure-prices` to a Gateway whose routes require
`/api/v1/...`, producing 404. This is deployment configuration, not a contract change.

## Proven behavior and limits

The shell serves `/login`, both versioned portal entries, `/auth-config.json`
and `/portal-registry.json` with 200. The development Auth/session resources
return 404. Real IAM login through the shell succeeds; both catalog GETs return
200 with that staff token. Real CSRF/logout succeeds and the revoked token then
receives 401 through the same shell. The fixture removes all its resources.
Ignored logs are under `target/real-iam-integration/evidence`.

These are HTTP deployment checks, not execution of the mounted UI in a browser.
The browser access host was unavailable; native browser automation also stopped
because it could not determine the active URL confidently enough to enforce its
safety policy. No security warning was bypassed and no browser acceptance is
claimed. Secure-cookie handling, UI creation/reload/logout and stage readiness
remain pending; test-only trust and identities retain the owner report's limits.
