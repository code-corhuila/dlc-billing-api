"""Actual HTTP payments/current PDF and service-only historical representations."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import unittest
from uuid import uuid4
from jsonschema import Draft202012Validator, FormatChecker
from appointment_platform import AppointmentPlatform
from integration_platform import ROOT
from invoice_fixture import create_draft


class FinancialPlatform(AppointmentPlatform):
    def billing_dependencies(self):
        env,mounts=super().billing_dependencies()
        return dict(env,BILLING_SERVICE_JWT_PUBLIC_KEY_FILE="/run/billing-service-trust/public.pem",
                    BILLING_SERVICE_JWT_ISSUER="integration-service-authority",BILLING_SERVICE_JWT_AUDIENCE="integration-billing",
                    BILLING_WORKER_SERVICE_SUBJECT="worker-integration"),[
                        *mounts,(self.directory/"service-public.pem","/run/billing-service-trust/public.pem")]

    def private(self,kind,invoice,event):
        output=self.run("docker","run","--rm","--network",self.prefix,"--mount",
                        f"type=bind,source={ROOT/'tests/integration'},target=/probe,readonly","--mount",
                        f"type=bind,source={self.directory/'worker'},target=/run/worker,readonly",
                        "maven:3.9-eclipse-temurin-21","java","/probe/FinancialProbe.java",kind,invoice,event)
        result=[line.removeprefix("FINANCIAL_PROBE:") for line in output.splitlines() if line.startswith("FINANCIAL_PROBE:")]
        return int(result[0]),result[1],base64.b64decode(result[2])


class PaymentPdfRealOwnersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p=FinancialPlatform(); cls.addClassCleanup(cls.p.close); cls.addClassCleanup(cls.capture); cls.p.start()
    @classmethod
    def capture(cls):
        logs={name:cls.p.run("docker","logs",name) for name in cls.p.names}
        for name,value in logs.items():
            for secret in cls.p.secrets: value=value.replace(secret,"[redacted]")
            logs[name]=value
        (ROOT/"target"/(cls.p.prefix+"-financial-logs.json")).write_text(json.dumps(logs),encoding="utf-8")
    def stored(self,sql):
        return self.p.run("docker","exec",self.p.pg,"psql","-U","postgres","-d","di_lucca","-Atc",sql).strip()
    def event(self,invoice,kind):
        raw=self.stored("SELECT event_body::text FROM billing.outbox_events WHERE aggregate_id='"+invoice+"' AND event_type='"+kind+"' ORDER BY occurred_at,event_id LIMIT 1")
        envelope=json.loads(raw)
        schema=json.loads((Path(os.environ["DLC_DOCS_SOURCE"])/("07-api/contracts/events/"+kind+".v1.schema.json")).read_text())
        Draft202012Validator(schema,format_checker=FormatChecker()).validate(envelope)
        return envelope
    def test_actual_payment_balance_replay_and_event_state_pdf_after_later_payment(self):
        p=self.p; token,cookie=p.login("ADMINISTRATOR"); assistant,_=p.login("SECRETARY_ASSISTANT"); dentist,_=p.login("DENTIST")
        p.wait(lambda:p.request("GET","/api/v1/invoices",token=token)[0]==200,"Billing/IAM HTTP readiness")
        p.sql("INSERT INTO billing.payment_methods(method) VALUES ('SYNTHETIC_LOCAL');")
        draft=create_draft(p,token); invoice=draft["id"]; base="/api/v1/invoices/"+invoice
        self.assertEqual(200,p.request("POST",base+"/issuances",dict(expectedVersion=1),token=token,key=str(uuid4()))[0])
        issue=self.event(invoice,"InvoiceIssued")
        p.run("docker","stop",p.prefix+"-broker")
        path=base+"/payments"; key=str(uuid4()); body=dict(amount="4.01",method="SYNTHETIC_LOCAL",expectedVersion=2)
        self.assertEqual(403,p.request("POST",path,body,token=dentist,key=key)[0])
        self.assertEqual(400,p.request("POST",path,dict(body,amount=4.01),token=token,key=key)[0])
        self.assertEqual(400,p.request("POST",path,dict(body,method="UNCONFIGURED"),token=token,key=key)[0])
        status,headers,first=p.request("POST",path,body,token=assistant,key=key)
        self.assertEqual(201,status); self.assertTrue(headers.get("Location",headers.get("location","")).startswith(path+"#"))
        payment=self.event(invoice,"PaymentRecorded")
        self.assertEqual("6.00",payment["payload"]["remainingBalance"])
        self.assertEqual(409,p.request("POST",path,dict(body,amount="6.01",expectedVersion=3),token=token,key=str(uuid4()))[0])
        self.assertEqual(409,p.request("POST",path,body,token=token,key=str(uuid4()))[0])
        second=dict(amount="6.00",method="SYNTHETIC_LOCAL",expectedVersion=3)
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses=list(pool.map(lambda _:p.request("POST",path,second,token=token,key=str(uuid4()))[0],range(2)))
        self.assertEqual([201,409],sorted(responses))
        self.assertEqual(first,p.request("POST",path,body,token=assistant,key=key)[2])
        self.assertEqual(409,p.request("POST",path,dict(body,amount="4.02"),token=assistant,key=key)[0])
        self.event(invoice,"InvoicePaid")
        current=json.loads(p.request("GET",base,token=assistant)[2]); self.assertEqual(("PAID","0.00",4),(current["status"],current["balance"],current["version"]))
        listed=json.loads(p.request("GET",path,token=assistant)[2]); self.assertEqual(2,listed["meta"]["total"])
        self.assertEqual([],json.loads(p.request("GET",path+"?page=2",token=assistant)[2])["data"])
        status,headers,pdf=p.request("GET",base+"/pdf",token=assistant)
        self.assertEqual(200,status); self.assertTrue(pdf.startswith(b"%PDF-"))
        for source,balance in ((issue,"10.01"),(payment,"6.00")):
            status,_,raw=p.private("summary",invoice,source["eventId"])
            self.assertEqual(200,status); summary=json.loads(raw); self.assertEqual(balance,summary["remainingBalance"])
            status,content,raw=p.private("pdf",invoice,source["eventId"])
            self.assertEqual((200,"application/pdf"),(status,content)); self.assertTrue(raw.startswith(b"%PDF-"))
        self.assertEqual(404,p.private("pdf",str(uuid4()),payment["eventId"])[0])
        p.logout(cookie)
        self.assertEqual(401,p.request("POST",path,second,token=token,key=str(uuid4()))[0])
