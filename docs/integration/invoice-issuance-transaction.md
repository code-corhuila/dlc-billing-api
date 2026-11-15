# Invoice issuance transaction

The local transaction implements BillingIssueinvoice using DB changesets 014/015.
The invoice header is locked before checking DRAFT and expectedVersion. Its
sources must be BILLED, belong to CHARGES_COMPLETED encounters, occur in the
declared procedure/manual sets and retain the terminal appointment proof from
draft allocation. Price evidence is either catalog_version or a nonblank
justification plus the verified role recorded at entry. The legacy approval flag
does not impose another approval. Missing evidence conflicts without a write.

The stored rounded line totals must equal the draft total. Issuance fixes number,
date and version while preserving amounts. The internal number policy is
BIL-{invoice UUID}; this is a stable unique Billing identifier, not tax invoicing
or a claim of a regulated sequential numbering scheme.

The same PostgreSQL transaction stores the InvoiceIssued v1 canonical envelope,
the immutable issued invoice representation, and the exact idempotent HTTP
response. Correlation metadata is retained from the first successful command;
the idempotency key is its causationId. Changed expectedVersion under the same
actor/key conflicts; unchanged retries replay before domain-state checks.
No broker, worker, PDF or email call is made during the financial transaction.

This transaction increment originally kept HTTP closed; the following
[HTTP increment](invoice-issuance-http.md) registers the service. Eight owner
tests passed, including same/different-key concurrency and late event/replay
failures. Owner tests use explicitly
synthetic stored encounters/pricing evidence, not a Clinical producer or manual
care-price HTTP workflow. Outbox relay, event-state PDF and delivery remain
separate work; an outbox row is durable intent, not proof of publication.
