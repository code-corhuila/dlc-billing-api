# Invoice issuance HTTP

POST /api/v1/invoices/{id}/issuances accepts only integer expectedVersion >= 1,
with a UUID Idempotency-Key. Strict parsing rejects duplicate/trailing JSON,
unknown fields and supplied amounts. Administrator and Assistant may issue;
JWT verification and live IAM eligibility precede every attempt and replay.
Actor identity comes exclusively from the verified JWT subject.

Success returns the persisted 200 Invoice representation. Missing invoices return
404; stale versions, changed retries and ineligible sources return 409.
Persistence failures return a safe 503 without SQL/provider details. No caller
can choose number, date, status, total, event body or pricing authority.

The first successful command's correlation UUID is recorded with the event.
Non-UUID trace strings map deterministically to UUIDs, matching the platform's
trace adaptation pattern; metadata changes do not change the business retry.
The operation requires DB changesets 014/015 and the prior draft/read migrations.

Four HTTP boundary tests passed with test doubles; the eight transaction tests
use real PostgreSQL and the limited runtime role, including late rollback after
both event/history writes. Broker publication, email and PDF are separate from
the committed financial result. See the real owner report for runtime evidence.
