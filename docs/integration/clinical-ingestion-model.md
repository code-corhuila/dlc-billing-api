# Clinical ingestion model

The decoder validates the three official v1 facts ProcedureCompleted,
ConsultationRecorded and ClinicalCareCompleted, strict duplicate/trailing JSON,
UUIDs, aggregate correlation, exact positive quantity and unique source sets.
The 256 KiB receiver bound rejects oversized deliveries rather than truncating.
Clinical reason is validated but never retained in the financial projection.
No new public endpoint or changed official contract is introduced.

RED compilation failed for the missing model. GREEN: three Java tests passed;
all three shared synthetic fixtures independently passed the official JSON Schemas
with format checking. These fixtures validate contracts, not a real producer flow.
Durable inbox, money reconciliation and transport follow in separate increments.
