"""Real HTTP invoice reads using explicit owner-local synthetic invoice fixtures."""
import json
import os
from pathlib import Path
import unittest
from uuid import uuid4
from shell_platform import ShellPlatform


class InvoiceReadIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p=ShellPlatform()
        cls.addClassCleanup(cls.p.close)
        cls.p.start()

    def test_real_invoice_list_detail_filtering_and_revocation(self):
        p=self.p
        token,cookie=p.login("ADMINISTRATOR")
        path="/api/v1/invoices"
        status,_,raw=p.request("GET",path,token=token)
        self.assertEqual(200,status)
        self.assertEqual([],json.loads(raw)["data"])
        fixture=(Path(os.environ["BILLING_DB_SOURCE"])/"tests/invoice-storage.sql").read_text()
        p.sql(fixture.split("SET LOCAL ROLE billing_runtime;")[0]+"COMMIT;")
        patient,account,appointment,item,invoice=[str(uuid4()) for _ in range(5)]
        p.sql(f"""BEGIN;
            INSERT INTO billing.encounter_accounts
            (encounter_account_id,clinical_record_id,consultation_id,appointment_id,patient_id)
            VALUES ('{account}','{uuid4()}','{uuid4()}','{appointment}','{patient}');
            INSERT INTO billing.billable_items
            (item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,
             item_type,description,quantity,unit_price,created_by,status,justification)
            VALUES ('{item}','{account}','{patient}','{appointment}','{uuid4()}','{uuid4()}',
            'ADDITIONAL_MATERIAL','Second financial fixture',1,10.01,'{uuid4()}','BILLED','Protected fixture');
            INSERT INTO billing.invoices(invoice_id,patient_id,total,balance,created_by,created_at)
            VALUES ('{invoice}','{patient}',10.01,10.01,'{uuid4()}','2026-11-06T00:00:00Z');
            INSERT INTO billing.invoice_items(invoice_id,patient_id,item_id)
            VALUES ('{invoice}','{patient}','{item}');
            COMMIT;""")
        first="50000000-0000-4000-8000-000000000001"
        for page,identity in enumerate(sorted((first,invoice)),1):
            status,_,raw=p.request("GET",path+f"?page={page}&limit=1",token=token)
            self.assertEqual(200,status)
            value=json.loads(raw)
            self.assertEqual(identity,value["data"][0]["id"])
            self.assertEqual(2,value["meta"]["total"])
        status,_,raw=p.request("GET",path+"?patientId="+patient,token=token)
        self.assertEqual(200,status)
        self.assertEqual(1,json.loads(raw)["meta"]["total"])
        self.assertEqual(invoice,json.loads(raw)["data"][0]["id"])
        for identity in (first,invoice):
            status,_,raw=p.request("GET",path+"/"+identity,token=token)
            self.assertEqual(200,status)
            value=json.loads(raw)
            self.assertEqual("10.01",value["total"])
            self.assertEqual("10.01",value["balance"])
            self.assertEqual("10.01",value["items"][0]["unitPrice"])
            self.assertNotIn("createdBy",value)
            self.assertNotIn("justification",value["items"][0])
            self.assertNotIn("invoiceNumber",value)
        self.assertEqual(404,p.request("GET",path+"/"+str(uuid4()),token=token)[0])
        self.assertEqual(400,p.request("GET",path+"?patientId=1-1-1-1-1",token=token)[0])
        assistant,_=p.login("SECRETARY_ASSISTANT")
        self.assertEqual(200,p.request("GET",path+"/"+first,token=assistant)[0])
        dentist,_=p.login("DENTIST")
        self.assertEqual(403,p.request("GET",path,token=dentist)[0])
        p.logout(cookie)
        self.assertEqual(401,p.request("GET",path+"/"+first,token=token)[0])
