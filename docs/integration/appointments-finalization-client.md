# Appointment finalization owner client

The private read follows Appointments API develop 73ed387 and its internal
coordination contract: GET /internal/v1/appointments/{id}/encounter-status,
requiring appointments:encounter-status:read on a separate service credential.
The response has appointmentId, patientId, dentistId, status and version.

Only a matching patient/appointment with FINALIZADA and a positive integral
version is eligible. Missing appointments and known non-final states conflict.
Malformed, unexpected or unavailable owner responses fail closed as unavailable.
Credentials are reread per invocation, never taken from staff authentication.
There is no eligibility cache, redirect or automatic application retry.
HTTPS is required unless explicitly enabling development HTTP. The deadline
bounds headers and the complete body; buffering is capped at 4096 received bytes.
Errors contain neither owner payloads nor provider/transport causes.

Seven focused transport tests passed, including rotation, all non-final states,
identity mismatch, malformed/version/duplicate/trailing data, redirects, outage,
provider failures, oversized bodies and a delayed body after successful headers.
The local HTTP test server is a unit-test double, not evidence of live owner
integration. No POST is exposed by this increment; deployment credential wiring
and actual Appointments runtime verification remain required.
