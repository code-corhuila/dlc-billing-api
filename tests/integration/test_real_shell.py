"""Opt-in real shell deployment checks, without development Auth or portal doubles."""
import json
import unittest
from uuid import uuid4
from shell_platform import ShellPlatform


class RealShellTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.platform = ShellPlatform()
        cls.addClassCleanup(cls.platform.close)
        cls.platform.start()

    def test_assets_and_authenticated_catalog_through_shell(self):
        p = self.platform
        for path in ("/login", "/auth-config.json", "/portal-registry.json",
                     "/portals/iam/0.1.0-local/entry.js", "/portals/billing/2.0.0/entry.js"):
            self.assertEqual(200, p.request("GET", path)[0], path)
        for path in ("/dev-auth.js", "/dev-session.json"):
            self.assertEqual(404, p.request("GET", path)[0], path)
        token, cookie = p.login("ADMINISTRATOR")
        self.assertEqual(200, p.request("GET", "/api/v1/procedure-prices", token=token)[0])
        self.assertEqual(200, p.request("GET", "/api/v1/additional-price-rules", token=token)[0])
        p.logout(cookie)
        self.assertEqual(401, p.request("GET", "/api/v1/procedure-prices", token=token)[0])

    def test_catalog_writes_survive_refresh_and_revalidate_logout(self):
        p = self.platform
        token, cookie = p.login("ADMINISTRATOR")
        status, _, raw = p.request("GET", "/api/v1/auth/csrf", cookie=cookie)
        self.assertEqual(200, status)
        proof = json.loads(raw)["csrfToken"]
        p.secrets.append(proof)
        status, headers, raw = p.request("POST", "/api/v1/auth/refresh", cookie=cookie, proof=proof)
        self.assertEqual(200, status)
        refreshed = json.loads(raw)["accessToken"]
        rotated_cookie = headers["Set-Cookie"].split(";", 1)[0]
        p.secrets.extend((refreshed, rotated_cookie))
        cookie_attributes = headers["Set-Cookie"].lower()
        for attribute in ("secure", "httponly", "samesite=strict"):
            self.assertTrue(attribute in cookie_attributes, "Missing cookie attribute: "+attribute)

        scenarios = [
            ("/api/v1/procedure-prices", "basePrice", dict(
                procedureCode="SHELL-"+uuid4().hex[:12], name="Shell integration price",
                basePrice="45678.91", validFrom="2026-11-06T00:00:00Z")),
            ("/api/v1/additional-price-rules", "unitPrice", dict(
                code="SHELL-"+uuid4().hex[:12], itemType="OTHER_AUTHORIZED_CHARGE",
                unitPrice="345.67", requiresApproval=False, validFrom="2026-11-06T00:00:00Z")),
        ]
        replays = []
        for path, money, body in scenarios:
            with self.subTest(path=path):
                key = str(uuid4())
                self.assertEqual(400, p.request("POST", path, dict(body, **{money: 12.34}),
                                                refreshed, str(uuid4()))[0])
                status, headers, created = p.request("POST", path, body, refreshed, key)
                self.assertEqual(201, status)
                resource = json.loads(created)
                self.assertEqual(body[money], resource[money])
                self.assertEqual(path+"/"+resource["id"], headers["Location"])
                status, replay_headers, replay = p.request("POST", path, body, refreshed, key)
                self.assertEqual(201, status)
                self.assertEqual(created, replay)
                self.assertEqual(headers["Location"], replay_headers["Location"])
                self.assertEqual(409, p.request("POST", path, dict(body, **{money: "19.99"}),
                                                refreshed, key)[0])
                status, _, page = p.request("GET", path+"?page=1&limit=100", token=refreshed)
                self.assertEqual(200, status)
                self.assertTrue(any(row["id"] == resource["id"] and row[money] == body[money]
                                    for row in json.loads(page)["data"]))
                replays.append((path, body, key))
        p.logout(rotated_cookie)
        for path, body, key in replays:
            self.assertEqual(401, p.request("POST", path, body, refreshed, key)[0])
            self.assertEqual(401, p.request("GET", path, token=token)[0])
