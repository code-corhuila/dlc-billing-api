"""Explicit synthetic owner facts followed by actual HTTP draft allocation."""
import json
from uuid import uuid4


def create_draft(p,token,manual=False,complete=True):
    patient,appointment,account,procedure,item,actor=[str(uuid4()) for _ in range(6)]
    # Explicit stored synthetic clinical/pricing facts, not a provider workflow claim.
    p.sql(f"""INSERT INTO appointments.appointments
        (appointment_id,patient_id,dentist_id,start_at,end_at,clinic_timezone,reason,status,confirmation_status,
        reminder_due_at,reminder_status,version,created_at,created_by,updated_at,updated_by)
        VALUES ('{appointment}','{patient}','{uuid4()}','2026-10-01T14:00:00Z','2026-10-01T15:00:00Z',
        'America/Bogota','Synthetic issuance encounter','FINALIZADA','CONFIRMED','2026-09-30T14:00:00Z',
        'SENT',7,now(),'{actor}',now(),'{actor}');
        INSERT INTO billing.encounter_accounts(encounter_account_id,clinical_record_id,consultation_id,
        appointment_id,patient_id,status,declared_procedure_ids)
        VALUES ('{account}','{uuid4()}','{uuid4()}','{appointment}','{patient}',
        '{"CHARGES_COMPLETED" if complete else "DECLARED"}',ARRAY['{procedure}']::uuid[]);
        INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,
        source_record_id,item_type,description,quantity,unit_price,created_by,catalog_version,
        pricing_authority_role,justification,approval_required)
        VALUES ('{item}','{account}','{patient}','{appointment}','{procedure}','{uuid4()}',
        'BASE_PROCEDURE','Synthetic issuance source',1,10.01,'{actor}',{"NULL" if manual else "1"},
        {"'DENTIST'" if manual else "NULL"},'Protected owner pricing reason',{str(manual).lower()});""")
    status,_,raw=p.request("POST","/api/v1/invoices",dict(patientId=patient,billableItemIds=[item]),
                           token=token,key=str(uuid4()))
    if status!=201:
        error=json.loads(raw).get("message","")
        safe=error if error in {"Authentication could not be confirmed.","Invoice draft creation unavailable"} else "unclassified"
        raise AssertionError("Real invoice draft returned "+str(status)+" ("+safe+")")
    return json.loads(raw)

