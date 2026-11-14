# Private financial event representations

Official GET /internal/v1/invoices/{id}/pdf and /notification-summary require one
sourceEventId. Billing reads only the matching immutable issuance/payment receipt;
later payments cannot change the result. Wrong invoice/event pairs are 404. PDFs
are rendered on demand and summaries contain only the official financial fields.

A separate filter chain verifies RS256, mounted public key, issuer, audience,
issued/expiry timestamps, subject and jti. Routes additionally require the
configured worker identity and exact operation scope. The service converter uses
scopes independently of the staff role converter; no staff session is accepted
or checked on private routes. Default missing trust fails closed. The public key
is reread per request. compose.internal.yml mounts public trust only; provisioning
and renewal of operational service credentials remain deployment responsibilities.

TDD: RED missing event query; GREEN two HTTP tests use real signed RSA tokens to
verify separate trust, wrong key/audience/subject/scope denial and event projection.
PaymentTransactionTest also checks persisted historical state after full payment
and rejects correlation to a different invoice. The complete isolated build passed
163 tests with zero failures/errors/skips, all seven SQL suites and repeat migration.
Real owner HTTP delivery is verified in a separate integration increment.
