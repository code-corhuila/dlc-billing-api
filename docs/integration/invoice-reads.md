# Invoice list and detail integration

Local implementation follows the official Billing OpenAPI and owner model.
`GET /api/v1/invoices` reads persisted invoices with page/limit and optional
patientId; `GET /api/v1/invoices/{id}` returns the owned financial projection.
Unknown identifiers return 404, invalid input returns safe contract 400, and
unavailable persistence returns a generic 503 without SQL or connection details.

Administrator and Secretary/Assistant can read. Dentist remains denied until
both explicit financial permission and current patient assignment are verified.
No source narrative, justification or audit actor is exposed. Decimal strings
and optional unissued identity fields match the Portal's existing HTTP adapter.

The repository counts with the same filters used for rows, orders by createdAt
and id, and loads all items for the selected page in one bounded batch. It does
not issue an extra item query per invoice. The shared billable-item mapper keeps
both public projections consistent. Financial item data stays in immutable
owner snapshots. Optional paidTotal is not guessed without payment history.

Requires DB `feat/billing-invoice-read-storage` at `e612634`, changesets 010/011,
after the snapshot storage. Runtime has SELECT only on invoices/allocations.
No placeholder, draft creation, issuance, payment or PDF endpoint is added.
Those workflows still need authoritative care state and their own transactions.

RED commits reproduce missing persistence/controller and the new HTTP tests
cover listing, validation and unknown-invoice handling. The integration suite
uses explicit synthetic owner SQL fixtures with actual allocated billable items;
these do not claim a real Clinical event or an audited invoice creation workflow.
Run it with the eight [shell inputs](real-shell-local-verification.md), the
new DB source and Billing image:

```sh
python -m unittest discover -s tests/integration -p test_invoice_reads.py -v
```

Verification on 2026-11-06: all 113 Java tests passed with PostgreSQL, zero
skipped. The final `dlc-billing-api:invoice-read-test` image passed the real
shell/Gateway/IAM scenario in 138.613 seconds: empty list before fixtures,
stable pages, filtered counts, item projections, exact amounts, 400/404, role
denials and logout revocation. All temporary resources were removed. Ignored
logs are under `target/invoice-read-evidence`. This does not validate a mounted
browser UI or enable the remaining financial write workflows.
