"""Opt-in local proof with real owner services and disposable test identities."""
import json
import unittest
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4
from integration_platform import Platform


class RealIamBillingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.platform = Platform()
        cls.addClassCleanup(cls.platform.close)
        cls.platform.start()

    def test_prices_replay_conflict_roles_and_revocation(self):
        p = self.platform
        token, cookie = p.login("ADMINISTRATOR")
        key = str(uuid4())
        body = dict(procedureCode="E2E-"+uuid4().hex[:12], name="Integration procedure",
                    basePrice="12345.67", validFrom="2026-11-06T00:00:00Z")
        path = "/api/v1/procedure-prices"
        with ThreadPoolExecutor(max_workers=2) as workers:
            results=list(workers.map(lambda _:p.request("POST",path,body,token,key),range(2)))
        status, headers, created = results[0]
        self.assertEqual((201,created),(results[1][0],results[1][2]))
        self.assertEqual(201, status)
        self.assertTrue(headers["Location"].startswith(path+"/"))
        self.assertEqual("12345.67", json.loads(created)["basePrice"])
        status, again_headers, again = p.request("POST", path, body, token, key)
        self.assertEqual(201, status)
        self.assertEqual(created, again)
        self.assertEqual(headers["Location"], again_headers["Location"])
        self.assertEqual(409, p.request("POST", path, dict(body, basePrice="12345.68"), token, key)[0])
        status, _, version = p.request("POST",path,dict(body,basePrice="12346.00",
            validFrom="2026-11-07T00:00:00Z"),token,str(uuid4()))
        self.assertEqual(201,status)
        self.assertEqual(2,json.loads(version)["version"])
        p.sql("DO $$ BEGIN IF (SELECT count(*) FROM billing.procedure_prices WHERE procedure_code='"
              +body["procedureCode"]+"' AND version=1 AND valid_to='2026-11-07T00:00:00Z') <> 1 THEN "
              "RAISE EXCEPTION 'Previous validity was not closed'; END IF; END $$;")
        self.assertEqual(200, p.request("GET", path, token=token)[0])
        dentist, _ = p.login("DENTIST")
        self.assertEqual(403, p.request("POST", path, body, dentist, str(uuid4()))[0])
        self.assertEqual(200, p.request("GET", path, token=dentist)[0])
        p.logout(cookie)
        self.assertEqual(401, p.request("POST", path, body, token, key)[0])
        self.assertEqual(401, p.request("GET", path, token=token)[0])

    def test_additional_rules_and_live_iam_unavailability(self):
        p = self.platform
        token, cookie = p.login("ADMINISTRATOR")
        path = "/api/v1/additional-price-rules"
        body = dict(code="E2E-"+uuid4().hex[:12], itemType="ADDITIONAL_MATERIAL",
                    unitPrice="234.56", requiresApproval=False, validFrom="2026-11-06T00:00:00Z")
        key = str(uuid4())
        status, headers, created = p.request("POST", path, body, token, key)
        self.assertEqual(201, status)
        self.assertEqual("234.56", json.loads(created)["unitPrice"])
        self.assertEqual((status, created), (p.request("POST", path, body, token, key)[0],
                                           p.request("POST", path, body, token, key)[2]))
        assistant, _ = p.login("SECRETARY_ASSISTANT")
        self.assertEqual(200, p.request("GET", path, token=assistant)[0])
        self.assertEqual(403, p.request("POST", path, body, assistant, str(uuid4()))[0])
        self.assertEqual(404, p.request("POST", "/internal/v1/sessions/validations", {}, token)[0])
        p.stop_iam()
        try:
            self.assertEqual(503, p.request("GET", path, token=token)[0])
            self.assertEqual(503, p.request("POST", path, body, token, key)[0])
        finally:
            p.start_iam()
        p.logout(cookie)
        self.assertEqual(401, p.request("GET", path, token=token)[0])
