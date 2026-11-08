# Declared clinical charge reconciliation

Catalog selection uses ProcedureCompleted.completedAt, as explicitly chosen by
the user on 2026-11-07. Automatic pricing waits for the fixed Clinical declaration
and actual Billing freeze; it cannot preempt care-time manual pricing. Existing
manual snapshots are preserved exactly. Unknown/no-code extras and approval-gated
catalog rules remain unresolved rather than receiving a substituted/zero price.

The reconciler locks the encounter gate/account, verifies settled manual admissions,
records the fixed source set and keeps the freeze permanent. Missing source or
price remains pending. Matched catalog versions create immutable COP snapshots;
once every source is resolved, BillableItemsCreated v2 and EncounterChargesCompleted
v1 are appended with once-only receipts in the financial transaction. No broker
call occurs there. Replays and competing reconciliation do not reprice/re-publish.

RED: missing reconciler. GREEN: nine PostgreSQL tests across reconciliation,
source projection and inbox verify completedAt against a newer catalog version,
reordered facts, pending dependencies, exact totals and concurrent publication.
The additional closure-proof and late outbox rollback checks passed: nine tests,
zero failures/errors/skips in `target/clinical-close-proof-green.log`. Release
serializes its declaration absence check with projection and rejects a committed
Clinical declaration even before financial reconciliation creates the account.
The runner also verifies the manual-charge runtime grants against PostgreSQL.
