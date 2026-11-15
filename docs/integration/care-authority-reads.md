# Care authority contract boundary

The existing private Appointments client now reads both encounter state and the
current patient/dentist assignment using the exact routes in official
`internal-coordination.yaml` and Appointments develop 73ed387. It does not cache
assignment results or forward staff credentials. Finalization reads retain their
existing behavior, timeout, byte limit, origin restrictions and credential rotation.

Assignment responses must match both requested identities, a positive relationship
version and unique official source types consistent with `assigned`. Malformed or
unavailable authority responses fail closed. This prepares manual-charge authority;
it does not by itself implement the manual-charge HTTP operations.

RED compilation proved the missing reads. GREEN: ten tests, zero failures/errors/
skips in `target/care-authority-green.log`, including all seven existing client
regressions. Boundary tests isolate HTTP responses; they are not real-owner proof.

IAM 5d53afa currently emits an empty permissions array in RsaAccessTokenIssuer.
The official Billing contract requires a separate explicit financial permission
for Dentist reads. Roles alone cannot enable that access; the permission identifier
and actual grant/issuance workflow remain an owner integration requirement.
