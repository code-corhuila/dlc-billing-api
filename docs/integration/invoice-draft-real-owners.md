# Invoice draft real owner verification

## Verified behavior

On 2026-11-06 the real shell→Gateway→Billing→Appointments path returned 201
after real IAM login/private session eligibility. Two separately rounded 0.005
lines produced 0.02, with owner version 7 persisted and both sources allocated.
The detail was readable by Assistant. Reordering the item set replayed the exact
saved body; changing the patient or reallocating items conflicted. Competing
keys produced 201/409 and concurrent same-key requests returned identical 201s.

An omitted appointment field did not bypass EN_ATENCION, wrong-patient or missing
owner checks. With real Appointments stopped, a saved response remained replayable
but a new draft returned 503 with no invoice, source change or idempotency record.
After restart, an actual Gateway appointment read succeeded and that same key
created the pending draft. Dentist was denied and logout revoked draft replay.
No owner business response was stubbed. The separate full Billing build passed
130 tests with zero failures/errors/skips and all four PostgreSQL SQL suites.

## Reproducible local inputs

Use the existing real-IAM/shell test image inputs described in
[shell verification](real-shell-local-verification.md), with these revisions:

| Component | Local input |
| --- | --- |
| Billing API | fbe9935; dlc-billing-api:invoice-draft-test |
| Billing DB | f8430e9, including changesets 008–013 |
| Appointments API | official develop 73ed387; dlc-appointments-api:billing-integration-73ed387 |
| Appointments DB | official develop 5b09c81 |
| Billing Portal | ada0fa1; dlc-billing-portal:billing-integration-ada0fa1 |

Set APPOINTMENTS_DB_SOURCE to the inspected owner checkout and
APPOINTMENTS_RUNTIME_IMAGE to the table's image, alongside existing
IAM_DB_SOURCE, BILLING_DB_SOURCE and IAM/Billing/Gateway/Portal/front image
variables. Run `python -m unittest discover -s tests/integration
-p test_invoice_drafts.py -v` (as one command).

The fixture creates a fresh UUID-prefixed Docker network and PostgreSQL instance.
Both owner changelogs run with their limited migrators. Appointments migration
history stays in appointments_liquibase; bootstrap is repeated to revoke its
temporary database CREATE permission. Runtime identities cannot read the other
owner's schema. Only the test shell/Gateway publish loopback ports. Cleanup
removes owned containers, volumes, network and temporary secret files.

The test-only ephemeral service authority issues separate scoped/audience tokens
for Billing→IAM, Billing→Appointments and Appointments→IAM. Appointments receives
public keys, never IAM staff signing material. No production identity provider
or secret provisioning/renewal mechanism is implied.

## Limits

Appointments and Billing rows are explicitly synthetic owner-local fixtures.
They establish real HTTP owner validation and allocation, not an actual Clinical
completion/declaration producer. Patients is not deployed: the official
patients-api origin remains configured for unused scheduling operations, which
cannot succeed without that owner. Reminders, relay and consumers are explicitly
disabled for this encounter-read test; no event-delivery claim follows.

Real portal assets are served by the shell, but JavaScript rendering, user form
submission, Secure-cookie acceptance and stage TLS still need browser evidence.
Invoice issuance/PDF, payments and Clinical ingestion remain separate delivery
work. Stage deployments with btree_gist in public require a DBA compatibility
decision; the fresh-test bootstrap never relocates an installed extension.
