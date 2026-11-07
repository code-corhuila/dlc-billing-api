# Payment HTTP adapter

The official POST/GET /api/v1/invoices/{id}/payments paths are implemented for
Administrator/Assistant. Dentist remains denied until both explicit financial
permission and current-assignment authorization are implemented. Every request,
including idempotent replay, passes JWT and live IAM session validation.

POST accepts only positive exact two-decimal amount strings, a configured method
and an integral positive expectedVersion. Unknown/duplicate properties, numeric
money, invalid UUIDs/keys and trailing JSON are rejected before persistence.
GET validates pagination and returns stable createdAt/id ordering with metadata;
unknown invoice is 404. Location identifies the created payment as a fragment of
its official invoice payment collection, without inventing another GET endpoint.

TDD: two HTTP assertions failed before routing; all three tests passed after
implementation, covering validation, authorization/IAM and result/error mapping.
HTTP tests isolate collaborators; real owner integration is tracked separately.
