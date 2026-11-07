# Clinical event orchestration

The handler commits minimized source projection/inbox before attempting financial
reconciliation. A lost acknowledgement can safely invoke both phases again.
Malformed/conflicting source facts become permanent failures without including
message contents in exceptions. Financial/DB failures remain retryable.

A bounded keyset sweep revisits declarations whose charges are not complete.
Its in-memory cursor is only a fair scheduling hint; source facts and financial
state remain durable in PostgreSQL. Newly available catalog prices or a later
freeze can resolve a declaration without requiring a new Clinical event. A failed
row does not prevent the remaining batch from receiving an attempt.

RED proved the missing handler. GREEN: twelve real PostgreSQL tests, zero
failures/errors/skips (`target/clinical-event-handling-green.log`), including
reordered delivery, replay and later price/freeze recovery. Broker transport is
the next increment; this handler alone is not a deployed consumer.
