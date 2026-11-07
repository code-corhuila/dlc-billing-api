"""Real issuance with broker stopped; canonical event checked against owner schema."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import unittest
from uuid import uuid4
from jsonschema import Draft202012Validator, FormatChecker
from appointment_platform import AppointmentPlatform
from invoice_fixture import create_draft


class InvoiceIssuanceIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p=AppointmentPlatform()
        cls.addClassCleanup(cls.p.close)
        cls.p.start()

    def draft(self,token,manual=False,complete=True):
        return create_draft(self.p,token,manual,complete)

    def stored(self,sql):
        return self.p.run("docker","exec",self.p.pg,"psql","-U","postgres","-d","di_lucca","-Atc",sql).strip()

    def test_real_issuance_with_broker_down_and_immutable_canonical_event(self):
        p=self.p
        admin,cookie=p.login("ADMINISTRATOR")
        assistant,_=p.login("SECRETARY_ASSISTANT")
        dentist,_=p.login("DENTIST")
        draft=self.draft(admin)
        path="/api/v1/invoices/"+draft["id"]+"/issuances"
        body=dict(expectedVersion=1)
        key=str(uuid4())
        self.assertEqual(401,p.request("POST",path,body,key=key)[0])
        self.assertEqual(403,p.request("POST",path,body,token=dentist,key=key)[0])
        self.assertEqual(400,p.request("POST",path,dict(body,total="0.01"),token=admin,key=key)[0])
        self.assertEqual(409,p.request("POST",path,dict(expectedVersion=2),token=admin,key=key)[0])
        self.assertEqual(404,p.request("POST","/api/v1/invoices/"+str(uuid4())+"/issuances",body,token=admin,key=key)[0])
        p.run("docker","stop",p.prefix+"-broker")
        status,_,raw=p.request("POST",path,body,token=admin,key=key)
        self.assertEqual(200,status)
        issued=json.loads(raw)
        self.assertEqual("ISSUED",issued["status"])
        self.assertEqual(2,issued["version"])
        self.assertEqual("10.01",issued["total"])
        self.assertEqual("10.01",issued["balance"])
        self.assertTrue(issued["invoiceNumber"])
        self.assertTrue(issued["issuedAt"])
        self.assertEqual(raw,p.request("POST",path,body,token=admin,key=key)[2])
        self.assertEqual(409,p.request("POST",path,dict(expectedVersion=2),token=admin,key=key)[0])
        self.assertEqual(409,p.request("POST",path,body,token=admin,key=str(uuid4()))[0])
        status,_,detail=p.request("GET","/api/v1/invoices/"+draft["id"],token=assistant)
        self.assertEqual(200,status)
        self.assertEqual(issued,json.loads(detail))
        event=json.loads(self.stored("SELECT event_body::text FROM billing.outbox_events WHERE aggregate_id='"+draft["id"]+"'"))
        schema=json.loads((Path(os.environ["DLC_DOCS_SOURCE"])/"07-api/contracts/events/InvoiceIssued.v1.schema.json").read_text())
        Draft202012Validator(schema,format_checker=FormatChecker()).validate(event)
        self.assertEqual(issued["issuedAt"],event["payload"]["issuedAt"])
        self.assertEqual("10.01",event["payload"]["total"])
        self.assertEqual(key,event["metadata"]["causationId"])
        self.assertEqual(event["payload"]["issuedBy"],event["metadata"]["actorUserId"])
        snapshot=json.loads(self.stored("SELECT invoice_snapshot::text FROM billing.invoice_issuances WHERE invoice_id='"+draft["id"]+"'"))
        self.assertEqual(issued,snapshot)
        self.assertNotIn("justification",raw.decode())
        pending=self.draft(admin,complete=False)
        self.assertEqual(409,p.request("POST","/api/v1/invoices/"+pending["id"]+"/issuances",body,token=admin,key=str(uuid4()))[0])
        manual=self.draft(assistant,manual=True)
        manual_path="/api/v1/invoices/"+manual["id"]+"/issuances"
        same_key=str(uuid4())
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:p.request("POST",manual_path,body,token=assistant,key=same_key),range(2)))
        self.assertEqual([200,200],[result[0] for result in results])
        self.assertEqual(results[0][2],results[1][2])
        competing=self.draft(assistant)
        competing_path="/api/v1/invoices/"+competing["id"]+"/issuances"
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:p.request("POST",competing_path,body,token=assistant,key=str(uuid4()))[0],range(2)))
        self.assertEqual([200,409],sorted(results))
        self.assertEqual("3",self.stored("SELECT count(*) FROM billing.outbox_events"))
        self.assertEqual("3",self.stored("SELECT count(*) FROM billing.invoice_issuances"))
        p.logout(cookie)
        self.assertEqual(401,p.request("POST",path,body,token=admin,key=key)[0])
