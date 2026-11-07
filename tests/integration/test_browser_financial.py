"""Real Chromium financial Portal acceptance on disposable owner services."""
import unittest
from appointment_platform import AppointmentPlatform
from integration_platform import ROOT
from invoice_fixture import create_draft


class BrowserFinancialTest(unittest.TestCase):
    def test_real_login_issue_two_payments_three_pdfs_and_logout(self):
        p=AppointmentPlatform(); self.addCleanup(p.close); p.start()
        token,_=p.login("ADMINISTRATOR")
        p.wait(lambda:p.request("GET","/api/v1/invoices",token=token)[0]==200,"Billing/IAM readiness")
        p.sql("INSERT INTO billing.payment_methods(method) VALUES ('SYNTHETIC_LOCAL');")
        try:
            invoice=create_draft(p,token)["id"]
        except AssertionError:
            for service in ("billing-service","appointments-api"):
                output=p.run("docker","logs",p.prefix+"-"+service)
                for secret in p.secrets: output=output.replace(secret,"[redacted]")
                (ROOT/"target"/("browser-"+service+"-diagnostic.log")).write_text(output,encoding="utf-8")
            raise
        evidence=ROOT/"target"/(p.prefix+"-browser"); evidence.mkdir()
        env=dict(BILLING_BROWSER_ORIGIN=p.origin,BILLING_BROWSER_PROXY="http://"+p.prefix+"-front:80",
                 BILLING_BROWSER_INVOICE=invoice,BILLING_BROWSER_EMAIL=p.users["ADMINISTRATOR"],BILLING_BROWSER_PASSWORD=p.password)
        args=["docker","run","--rm","--init","--shm-size=512m","--network",p.prefix,
              "--mount",f"type=bind,source={ROOT/'tests/integration'},target=/probe,readonly",
              "--mount",f"type=bind,source={ROOT/'target/browser-runtime'},target=/runtime,readonly",
              "--mount",f"type=bind,source={evidence},target=/evidence"]
        for key in env: args += ["-e",key]
        result=p.run(*args,"mcr.microsoft.com/playwright:v1.65.0-noble","node","/probe/browser-financial.cjs",env=env)
        self.assertIn("BROWSER_FINANCIAL:PASS",result)
        # Check owner state without depending on the setup login's session policy.
        state=p.run("docker","exec",p.pg,"psql","-U","postgres","-d","di_lucca","-Atc",
                    "SELECT status||':'||balance FROM billing.invoices WHERE invoice_id='"+invoice+"'").strip()
        self.assertEqual("PAID:0.00",state)
