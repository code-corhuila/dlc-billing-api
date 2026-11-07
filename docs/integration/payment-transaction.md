# Atomic internal payments

DB changesets 018/019 extend the immutable invoice lifecycle forward. No applied
changeset is edited. The owner configures payment_methods; there are no enabled
defaults. Runtime can read methods, insert/read payments and receipts, but cannot
edit configuration, rewrite payments or delete history.

JdbcPaymentRecording locks the invoice after actor-scoped idempotency replay.
It rejects stale versions, unpayable state and excess balance. An invoice trigger
also locks/validates each payment and derives balance, status and version; direct
balance changes without the matching immutable payment sum fail. PaymentRecorded,
the event-state invoice receipt, conditional InvoicePaid and replay commit in the
same transaction. Broker/email failures cannot undo that commit. Receipt snapshots
support delayed delivery without repricing items or storing PDF documents.

RED compilation failed for the missing service. GREEN: 11 real PostgreSQL tests
(three payment and eight issuance), zero failures/errors/skips, plus seven SQL
suites and repeat migrations. Payment tests cover partial/full settlement, method
deactivation with authorized replay, altered payload/stale/overpayment conflicts,
concurrent keys, duplicate concurrent replay and rollback after outbox failure.
This increment does not establish HTTP, browser or notification delivery.
