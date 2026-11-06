"""Opt-in real shell deployment checks, without development Auth or portal doubles."""
import unittest
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
