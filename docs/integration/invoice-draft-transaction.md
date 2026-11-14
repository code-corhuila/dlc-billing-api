# Atomic invoice draft allocation

Local transaction increment after invoice reads. The draft command accepts only
patient, optional appointment and unique billable source identities. Source IDs
are normalized as a set for idempotency. The operation uses the invoice collection
as its idempotency resource; changing patient/appointment/items under the same
actor/key conflicts. The saved 201 response and allocation commit together.

Billing locks sources in UUID order, rejects missing/mixed/already allocated
items, and derives every appointment from stored sources. Omitting appointmentId
cannot avoid finalization validation; an explicit conflicting appointment fails.
Each distinct appointment must have an authoritative FINALIZADA result for the
same patient. The verified version/time are retained in owner-local audit rows.
Appointments treats FINALIZADA as terminal; this is not the admission protocol
for writes concurrent with care closure.

The total sums the immutable rounded line totals, with a Money-range check.
Allocation marks sources BILLED and increments their version. Failure anywhere
rolls back invoice, links, source status, validation evidence and idempotency.
Replays return the original representation after normal HTTP authentication and
authorization, without another external domain read for the terminal fact.

Six PostgreSQL tests passed with the limited billing_runtime user: per-line
rounding, source/patient checks, exact replay, total overflow, late allocation
failure rollback, competing keys and concurrent same-key replay. All four DB SQL
suites and repeat migration also passed. Set BILLING_TEST_RUNTIME_DB_PASSWORD
in the disposable test process, alongside existing BILLING_TEST_DB_* inputs.
Test-only authority doubles exercise the transaction boundary; they do not prove
a live Appointments validation. The service is not yet registered as an HTTP POST.
The next increment must wire the real private owner client before exposing it.
