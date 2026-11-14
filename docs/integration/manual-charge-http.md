# Manual charge HTTP creation

The official POST `/api/v1/manual-charges` now connects strict input validation to
the proven-source financial transaction. Administrator and Dentist are allowed;
Assistant is denied. The server derives actor/authority/time. IAM session checks
precede every attempt and replay. Successful responses retain the persisted 201,
Location and protected representation with no-store caching.

Care/assignment reads use the existing configured private Appointments client and
rotatable mounted service credential. Missing configuration or authority outages
fail closed. Dentist operation requires `assignments:read` in addition to
`appointments:encounter-status:read`; this changes no public contract or staff JWT.

RED: three HTTP tests failed because the route was denied with 403. GREEN:
eleven tests, zero failures/errors/skips, including manual HTTP, input and invoice
draft HTTP regression (`target/manual-charge-http-green.log`). These boundary
tests isolate IAM/recording; real owner manual acceptance remains a later gate.
Protected GET `/api/v1/manual-charges/{id}` is now implemented in the next local
increment. Administrator may read it; the source Dentist must also retain a live
assignment. Scope is applied in the owner query, and missing/out-of-scope records
share 404. The DTO includes protected justification only on this protected route
and uses no-store caching. Fourteen PostgreSQL/HTTP/input tests passed with zero
failures/errors/skips (`target/manual-charge-read-green.log`). Price-correction
POST remains separate; real owner manual acceptance is still pending.
