"""Real owner HTTP issuance, mandatory routing, broker outage and recovery."""
import base64
import json
import os
from pathlib import Path
import unittest
from uuid import uuid4
from zipfile import ZipFile
from jsonschema import Draft202012Validator, FormatChecker
from appointment_platform import AppointmentPlatform
from integration_platform import ROOT
from invoice_fixture import create_draft


class RelayPlatform(AppointmentPlatform):
    def before_billing(self):
        super().before_billing()
        self.uri="amqp://integration:"+self.secrets[5]+"@broker:5672"
        self.secrets.append(self.uri)
        self.broker_directory=self.directory/"rabbitmq"
        self.broker_directory.mkdir()
        (self.broker_directory/"uri").write_text(self.uri)
        self.libraries=self.directory/"probe-libraries"; self.libraries.mkdir()
        with ZipFile(os.environ["BILLING_PROBE_JAR"]) as artifact:
            for member in artifact.namelist():
                if member.startswith("BOOT-INF/lib/") and member.endswith(".jar"):
                    (self.libraries/Path(member).name).write_bytes(artifact.read(member))

    def billing_dependencies(self):
        env,mounts=super().billing_dependencies()
        return dict(env,BILLING_OUTBOX_RELAY_ENABLED="true",BILLING_OUTBOX_INTERVAL="PT1S",
                    BILLING_RABBITMQ_LOCAL_DEVELOPMENT="true",BILLING_RABBITMQ_URI_FILE="/run/billing-rabbitmq/uri"),[
                        *mounts,(self.broker_directory,"/run/billing-rabbitmq")]

    def observe(self,operation):
        output=self.run("docker","run","--rm","--network",self.prefix,"--mount",
                        f"type=bind,source={ROOT/'tests/integration'},target=/probe,readonly","--mount",
                        f"type=bind,source={self.libraries},target=/libraries,readonly","-e","BILLING_PROBE_URI",
                        "maven:3.9-eclipse-temurin-21","java","--class-path","/libraries/*",
                        "/probe/RabbitProbe.java",operation,self.prefix+"-invoice-observer",
                        env={"BILLING_PROBE_URI":self.uri})
        return [line.removeprefix("BILLING_PROBE:") for line in output.splitlines() if line.startswith("BILLING_PROBE:")]


class OutboxPublicationIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p=RelayPlatform(); cls.addClassCleanup(cls.p.close); cls.addClassCleanup(cls.capture); cls.p.start()

    @classmethod
    def capture(cls):
        logs={name:cls.p.run("docker","logs",name) for name in cls.p.names}
        for name,value in logs.items():
            for secret in cls.p.secrets: value=value.replace(secret,"[redacted]")
            logs[name]=value
        (ROOT/"target"/(cls.p.prefix+"-publication-logs.json")).write_text(json.dumps(logs),encoding="utf-8")

    def stored(self,sql):
        return self.p.run("docker","exec",self.p.pg,"psql","-U","postgres","-d","di_lucca","-Atc",sql).strip()

    def issue(self,token):
        draft=create_draft(self.p,token)
        path="/api/v1/invoices/"+draft["id"]+"/issuances"
        key=str(uuid4())
        status,_,raw=self.p.request("POST",path,dict(expectedVersion=1),token=token,key=key)
        self.assertEqual(200,status)
        return draft["id"],path,key,raw

    def delivery(self,invoice):
        return self.stored("SELECT d.status||':'||d.attempts FROM billing.outbox_deliveries d "
                           "JOIN billing.outbox_events e USING(event_id) WHERE e.aggregate_id='"+invoice+"'")

    def attempted(self,invoice):
        status,attempts=self.delivery(invoice).split(":")
        return status=="PENDING" and int(attempts)>0

    def received(self,invoice):
        observed=self.p.observe("receive")
        self.assertEqual(5,len(observed))
        envelope=json.loads(base64.b64decode(observed[0]))
        self.assertEqual(envelope["eventId"],observed[1])
        self.assertEqual(["InvoiceIssued","application/json","2"],observed[2:])
        stored=json.loads(self.stored("SELECT event_body::text FROM billing.outbox_events WHERE aggregate_id='"+invoice+"'"))
        self.assertEqual(stored,envelope)
        schema=json.loads((Path(os.environ["DLC_DOCS_SOURCE"])/"07-api/contracts/events/InvoiceIssued.v1.schema.json").read_text())
        Draft202012Validator(schema,format_checker=FormatChecker()).validate(envelope)
        return envelope

    def test_actual_route_confirmation_outage_recovery_and_stable_financial_response(self):
        p=self.p; token,cookie=p.login("ADMINISTRATOR")
        p.wait(lambda:p.request("GET","/api/v1/invoices",token=token)[0]==200,"Billing/IAM HTTP readiness")
        first,path,key,raw=self.issue(token)
        p.wait(lambda:self.attempted(first),"Mandatory unroutable event retained")
        self.assertEqual(["BOUND"],p.observe("bind"))
        p.wait(lambda:self.delivery(first).startswith("PUBLISHED:"),"Confirmed event after binding")
        first_event=self.received(first)
        self.assertEqual(raw,p.request("POST",path,dict(expectedVersion=1),token=token,key=key)[2])
        p.run("docker","stop",p.prefix+"-broker")
        second,path2,key2,raw2=self.issue(token)
        p.wait(lambda:self.attempted(second),"Failed broker attempt retained while down")
        self.assertEqual("ISSUED",json.loads(raw2)["status"])
        p.run("docker","start",p.prefix+"-broker")
        p.wait(lambda:self.delivery(second).startswith("PUBLISHED:"),"Confirmed recovery after broker restart")
        self.assertGreater(int(self.delivery(second).split(":")[1]),1)
        second_event=self.received(second)
        self.assertNotEqual(first_event["eventId"],second_event["eventId"])
        (p.broker_directory/"uri").write_text("invalid-test-credential")
        third,path3,key3,raw3=self.issue(token)
        p.wait(lambda:self.attempted(third),"Failed credential attempt retained")
        (p.broker_directory/"uri").write_text(p.uri)
        p.wait(lambda:self.delivery(third).startswith("PUBLISHED:"),"Credential reread without API restart")
        third_event=self.received(third)
        self.assertEqual(3,len({first_event["eventId"],second_event["eventId"],third_event["eventId"]}))
        self.assertEqual(raw3,p.request("POST",path3,dict(expectedVersion=1),token=token,key=key3)[2])
        self.assertEqual(["EMPTY"],p.observe("receive"))
        self.assertEqual(raw2,p.request("POST",path2,dict(expectedVersion=1),token=token,key=key2)[2])
        self.assertEqual("3",self.stored("SELECT count(*) FROM billing.invoice_issuances"))
        p.logout(cookie)
        self.assertEqual(401,p.request("POST",path2,dict(expectedVersion=1),token=token,key=key2)[0])
