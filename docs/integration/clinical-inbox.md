# Durable clinical receipts

JdbcClinicalInbox validates original envelopes before persistence. It serializes
competing deliveries by event ID, compares a canonical original JSON hash and
rejects altered reuse. Only minimized typed financial facts are stored; neither
clinicalReason nor its text survives. The original body exists only during
validation/hash calculation and is never logged or retained.

Transport may acknowledge only after the surrounding local transaction commits.
Projection and the one-way projected timestamp can join that transaction; source
projection/financial completion are separate from receiving a receipt.

RED: missing receiver. GREEN: three real PostgreSQL tests using runtime verify
durability, identical/concurrent duplicates, altered replay, invalid producer,
private typed restoration and one-way projection markers; nine SQL suites passed.
The preceding full restored build passed 171 Java tests without skips.

Business decision confirmed by the user on 2026-11-07: catalog base pricing selects
the version effective at ProcedureCompleted.payload.completedAt. Later catalog
changes cannot reprice committed snapshots. Explicit manual prices stay exact.
