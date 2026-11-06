"""Real Docker packaging checks; these do not establish live IAM integration."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "dlc-billing-api:deployment-test"


def docker(*args, env=None):
    return subprocess.run(["docker", *args], cwd=ROOT, env=env,
                          capture_output=True, text=True, timeout=180)


class ComposeTest(unittest.TestCase):
    def test_requires_explicit_stage_environment_file_and_network(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith("BILLING_")}
        result = docker("compose", "-f", "deploy/compose.yml", "config", "--quiet", env=env)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("BILLING_STAGE", result.stderr)

    def test_joins_only_selected_network_and_has_no_published_ports(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "runtime.env"
            config.write_text("IAM_JWT_ISSUER=compose-test-only\n", encoding="utf-8")
            env = dict(os.environ, BILLING_STAGE="develop", BILLING_ENV_FILE=str(config),
                       BILLING_PLATFORM_NETWORK="billing-deployment-test-network")
            result = docker("compose", "-f", "deploy/compose.yml", "config", "--format", "json", env=env)
            self.assertEqual(0, result.returncode, result.stderr)
            manifest = json.loads(result.stdout)
            self.assertEqual({"billing-service"}, set(manifest["services"]))
            service = manifest["services"]["billing-service"]
            self.assertFalse(service.get("ports"))
            self.assertEqual(["8080"], service["expose"])
            self.assertEqual({"platform"}, set(service["networks"]))
            self.assertTrue(manifest["networks"]["platform"]["external"])
            self.assertEqual("billing-deployment-test-network", manifest["networks"]["platform"]["name"])


class RuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        result = docker("build", "-t", IMAGE, ".")
        if result.returncode:
            raise AssertionError("Billing image build failed:\n" + result.stderr[-3000:])
        cls.environment = dict(os.environ)
        for suffix in ("JDBC_URL", "DB_USER", "DB_PASSWORD"):
            if not cls.environment.get("BILLING_TEST_" + suffix):
                raise AssertionError("Set isolated BILLING_TEST_" + suffix)
        cls.environment.update(
            SPRING_DATASOURCE_URL=cls.environment["BILLING_TEST_JDBC_URL"],
            SPRING_DATASOURCE_USERNAME=cls.environment["BILLING_TEST_DB_USER"],
            SPRING_DATASOURCE_PASSWORD=cls.environment["BILLING_TEST_DB_PASSWORD"],
            BILLING_IAM_JWKS_URI="http://auth-service:8080/api/v1/auth/jwks",
            IAM_JWT_ISSUER="container-verification-only", IAM_JWT_AUDIENCE="container-verification-only")

    def start(self, configure_iam=True):
        name = "billing-runtime-test-" + uuid4().hex
        args = ["run", "-d", "--name", name, "--network", "dlc-billing-test-net",
                "-p", "127.0.0.1::8080"]
        for key in self.environment:
            if key.startswith("SPRING_DATASOURCE_") or configure_iam and key in (
                    "BILLING_IAM_JWKS_URI", "IAM_JWT_ISSUER", "IAM_JWT_AUDIENCE"):
                args.extend(["-e", key])
        result = docker(*args, IMAGE, env=self.environment)
        self.assertEqual(0, result.returncode, "Could not start isolated Billing container")
        self.addCleanup(lambda: docker("rm", name))
        self.addCleanup(lambda: docker("stop", name))
        return name

    def test_image_uses_nonroot_user(self):
        result = docker("image", "inspect", IMAGE)
        self.assertEqual(0, result.returncode)
        config = json.loads(result.stdout)[0]["Config"]
        self.assertNotIn(config["User"].split(":")[0], ("", "root", "0"))
        self.assertIn("8080/tcp", config["ExposedPorts"])

    def test_runtime_rejects_missing_forged_and_malformed_authentication(self):
        name = self.start()
        mapping = docker("port", name, "8080/tcp").stdout.strip()
        base = "http://" + mapping
        deadline = time.monotonic() + 90
        while True:
            try:
                urlopen(base + "/api/v1/procedure-prices", timeout=2).close()
            except HTTPError as error:
                self.assertEqual(401, error.code)
                break
            except (URLError, ConnectionError, TimeoutError):
                self.assertLess(time.monotonic(), deadline, "Billing failed to become HTTP-ready")
                time.sleep(1)
            else:
                self.fail("Unauthenticated catalog request was accepted")
        for path in ("procedure-prices", "additional-price-rules"):
            for method in ("GET", "POST"):
                for headers in ({}, {"X-User-Id": str(uuid4()), "X-User-Role": "ADMINISTRATOR"},
                                {"Authorization": "Bearer malformed-jwt"}):
                    with self.subTest(path=path, method=method, headers=list(headers)):
                        with self.assertRaises(HTTPError) as denied:
                            urlopen(Request(base + "/api/v1/" + path, method=method, headers=headers), timeout=5)
                        self.assertEqual(401, denied.exception.code)
                        self.assertIn("Bearer", denied.exception.headers["WWW-Authenticate"])

    def test_runtime_refuses_to_start_without_iam_configuration(self):
        name = self.start(configure_iam=False)
        deadline = time.monotonic() + 90
        while True:
            result = docker("inspect", "--format", "{{.State.Running}}", name)
            if result.returncode or result.stdout.strip() == "false":
                break
            self.assertLess(time.monotonic(), deadline, "Billing started without required IAM configuration")
            time.sleep(1)
        exit_code = docker("inspect", "--format", "{{.State.ExitCode}}", name)
        self.assertEqual(0, exit_code.returncode)
        self.assertNotEqual("0", exit_code.stdout.strip())
        logs = docker("logs", name)
        self.assertIn("BILLING_IAM_JWKS_URI", logs.stdout + logs.stderr)
