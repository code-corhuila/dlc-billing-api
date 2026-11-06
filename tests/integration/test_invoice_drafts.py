"""Actual shell/Gateway/IAM/Billing/Appointments on disposable owner databases."""
from concurrent.futures import ThreadPoolExecutor
import json
import unittest
from uuid import uuid4
from appointment_platform import AppointmentPlatform


class InvoiceDraftIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p=AppointmentPlatform()
        cls.addClassCleanup(cls.p.close)
        cls.p.start()

    def source(self,state="FINALIZADA",wrong_patient=False,missing=False):
        p=self.p
        patient,appointment,account,actor=[str(uuid4()) for _ in range(4)]
        items=[str(uuid4()),str(uuid4())]
        # Explicit synthetic owner state, not a claim of a Clinical completion workflow.
        if not missing:
            owner_patient=str(uuid4()) if wrong_patient else patient
            p.sql(f"""INSERT INTO appointments.appointments
                (appointment_id,patient_id,dentist_id,start_at,end_at,clinic_timezone,reason,status,
                confirmation_status,reminder_due_at,reminder_status,version,created_at,created_by,updated_at,updated_by)
                VALUES ('{appointment}','{owner_patient}','{uuid4()}','2026-10-01T14:00:00Z',
                '2026-10-01T15:00:00Z','America/Bogota','Synthetic integration encounter','{state}',
                'CONFIRMED','2026-09-30T14:00:00Z','SENT',7,now(),'{actor}',now(),'{actor}');""")
        p.sql(f"""INSERT INTO billing.encounter_accounts
            (encounter_account_id,clinical_record_id,consultation_id,appointment_id,patient_id)
            VALUES ('{account}','{uuid4()}','{uuid4()}','{appointment}','{patient}');""")
        for item in items:
            p.sql(f"""INSERT INTO billing.billable_items
                (item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,
                item_type,description,quantity,unit_price,created_by,justification)
                VALUES ('{item}','{account}','{patient}','{appointment}','{uuid4()}','{uuid4()}',
                'ADDITIONAL_MATERIAL','Synthetic draft source',0.5000,0.01,'{actor}','Protected fixture');""")
        return dict(patientId=patient,billableItemIds=items),appointment

    def test_terminal_owner_validation_atomic_replay_and_real_failures(self):
        p=self.p
        path="/api/v1/invoices"
        admin,cookie=p.login("ADMINISTRATOR")
        assistant,_=p.login("SECRETARY_ASSISTANT")
        dentist,_=p.login("DENTIST")
        body,appointment=self.source()
        key=str(uuid4())
        self.assertEqual(401,p.request("POST",path,body,key=key)[0])
        self.assertEqual(403,p.request("POST",path,body,token=dentist,key=key)[0])
        status,headers,raw=p.request("POST",path,body,token=admin,key=key)
        self.assertEqual(201,status)
        invoice=json.loads(raw)
        location=next(value for name,value in headers.items() if name.lower()=="location")
        self.assertEqual(path+"/"+invoice["id"],location)
        self.assertEqual("DRAFT",invoice["status"])
        self.assertEqual("0.02",invoice["total"])
        self.assertEqual("0.02",invoice["balance"])
        self.assertEqual(appointment,invoice["appointmentId"])
        self.assertEqual(2,len(invoice["items"]))
        self.assertNotIn("justification",raw.decode())
        self.assertNotIn("invoiceNumber",invoice)
        self.assertEqual(200,p.request("GET",location,token=assistant)[0])
        # Same key and set, reordered, returns the exact stored response.
        reordered=dict(body,billableItemIds=list(reversed(body["billableItemIds"])))
        self.assertEqual(raw,p.request("POST",path,reordered,token=admin,key=key)[2])
        self.assertEqual(409,p.request("POST",path,dict(body,patientId=str(uuid4())),token=admin,key=key)[0])
        self.assertEqual(409,p.request("POST",path,body,token=admin,key=str(uuid4()))[0])
        invoice_id=invoice["id"]
        p.sql(f"""DO $$ BEGIN IF
            (SELECT count(*) FROM billing.invoice_appointment_validations WHERE invoice_id='{invoice_id}'
              AND appointment_id='{appointment}' AND appointment_version=7)<>1
            OR (SELECT count(*) FROM billing.billable_items WHERE item_id IN
              ('{body['billableItemIds'][0]}','{body['billableItemIds'][1]}') AND status='BILLED' AND version=2)<>2
            THEN RAISE EXCEPTION 'Allocation or owner proof missing'; END IF; END $$;""")
        # Omitting appointmentId cannot bypass the actual source appointment's state or patient.
        for options in (dict(state="EN_ATENCION"),dict(wrong_patient=True),dict(missing=True)):
            invalid,_=self.source(**options)
            self.assertEqual(409,p.request("POST",path,invalid,token=admin,key=str(uuid4()))[0])
        competing,_=self.source()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:p.request("POST",path,competing,token=assistant,key=str(uuid4()))[0],range(2)))
        self.assertEqual([201,409],sorted(results))
        repeated,_=self.source()
        same_key=str(uuid4())
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:p.request("POST",path,repeated,token=assistant,key=same_key),range(2)))
        self.assertEqual([201,201],[result[0] for result in results])
        self.assertEqual(results[0][2],results[1][2])
        pending,pending_appointment=self.source()
        pending_key=str(uuid4())
        p.run("docker","stop",p.appointments)
        # A saved terminal fact remains replayable; a new allocation cannot invent owner availability.
        self.assertEqual(raw,p.request("POST",path,body,token=admin,key=key)[2])
        self.assertEqual(503,p.request("POST",path,pending,token=admin,key=pending_key)[0])
        p.sql(f"""DO $$ BEGIN IF EXISTS(SELECT FROM billing.invoices WHERE patient_id='{pending['patientId']}')
            OR EXISTS(SELECT FROM billing.idempotency_records WHERE idempotency_key='{pending_key}')
            OR EXISTS(SELECT FROM billing.billable_items WHERE patient_id='{pending['patientId']}' AND status<>'UNBILLED')
            THEN RAISE EXCEPTION 'Unavailable owner left partial draft'; END IF; END $$;""")
        p.run("docker","start",p.appointments)
        p.wait(lambda:p.request("GET","/api/v1/appointments/"+pending_appointment,token=admin)[0]==200,
               "Appointments HTTP after restart")
        self.assertEqual(201,p.request("POST",path,pending,token=admin,key=pending_key)[0])
        p.logout(cookie)
        self.assertEqual(401,p.request("POST",path,body,token=admin,key=key)[0])
