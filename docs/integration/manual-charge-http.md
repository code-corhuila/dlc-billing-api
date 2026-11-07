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
Protected manual GET and price-correction POST are still separate increments.
