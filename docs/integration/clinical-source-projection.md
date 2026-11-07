# Clinical source projection

Inbox and immutable procedure/extra, consultation and declaration facts commit in
one PostgreSQL transaction. Event replay and business-source replay have separate
checks. Conflicting source reuse, patient/appointment mismatch and overlapping
declarations fail closed and roll back the new inbox receipt.

Declaration can precede its consultation/procedures; no money, placeholder account
or zero price is inferred from missing facts. The initial implementation serializes
clinical projection transactions with one owner-local advisory lock; this also
protects declaration source-set checks across concurrent events. No throughput
claim or cross-owner datastore access is implied.

RED: missing projector. GREEN: six PostgreSQL tests (three projection plus three
inbox), nine SQL suites and repeat migrations. Actual broker ingestion and priced
encounter reconciliation are separate increments.
