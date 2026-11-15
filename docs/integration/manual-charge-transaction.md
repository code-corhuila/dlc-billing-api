# Proven-source manual recording

The manual transaction resolves one existing minimized Clinical source using the
exact entered COP price, quantity, concept and justification. It requires unique
consultation correlation and current EN_ATENCION owner state. Dentist requests
also require source ownership and a live assignment; that assignment is checked
again on retries. A completed care state blocks new writes, while an authorized
idempotent retry can return its original result after the Billing gate freezes.

The gate serializes against closure and competing source resolutions. Admission,
financial snapshot, protected manual record, minimized ManualChargeRecorded and
idempotency receipt share one PostgreSQL transaction. No remote write occurs;
a local transaction failure leaves no ambiguous durable admission. The event
contains identifiers/actor/time/version only, never price or justification.

RED compilation proved the missing recorder. GREEN: thirteen tests with zero
failures/errors/skips, including five new real PostgreSQL tests, validation and
coordinator regressions (`target/manual-charge-transaction-green.log`). They
verify exact rounded totals, competing keys, frozen/closed rejection, replay and
late outbox failure rollback. Owner HTTP reads are isolated collaborators in
these persistence tests, not a claim of real Appointments acceptance.

This increment does not expose manual HTTP routes. An unreceived/unproven source
fails closed; no private Clinical source-validation endpoint exists in the current
official coordination contract. No placeholder money or fabricated pending source
is created to bridge that provider contract gap.
