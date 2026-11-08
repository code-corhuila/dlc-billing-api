"""Disposable infrastructure for actual owner runtimes, not provider response stubs."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT=Path(__file__).resolve().parents[2]
NGINX="nginx@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10"
ORIGIN="http://localhost:8080"


class Platform:
    def __init__(self,origin=ORIGIN):
        self.origin=origin
        self.prefix="billing-e2e-"+uuid4().hex[:10]
        self.names=[]
        self.network_created=False
        self.secrets=[secrets.token_urlsafe(32) for _ in range(8)]
        self.temporary=tempfile.TemporaryDirectory(prefix=self.prefix+"-",dir=ROOT/"target")
        self.directory=Path(self.temporary.name)
        self.password="DiLucca1-"+secrets.token_hex(16)
        self.secrets.append(self.password)

    def run(self,*args,env=None,stdin=None):
        result=subprocess.run(args,input=stdin,env=dict(os.environ,**(env or {})),
                              capture_output=True,text=True,timeout=600)
        if result.returncode:
            error=result.stderr[-2500:]
            for value in self.secrets: error=error.replace(value,"[redacted]")
            raise RuntimeError(args[0]+" operation failed: "+error)
        return result.stdout

    def container(self,alias,image,env=None,mounts=(),publish=False):
        name=self.prefix+"-"+alias
        args=["docker","run","-d","--name",name,"--network",self.prefix,"--network-alias",alias,
              "--label","com.dilucca.integration="+self.prefix]
        for key,value in (env or {}).items():
            args += ["-e",key]
            if any(part in key for part in ("PASSWORD","KEY","TOKEN")): self.secrets.append(value)
        for source,target in mounts: args += ["--mount",f"type=bind,source={source},target={target},readonly"]
        if publish: args += ["-p","127.0.0.1::8080"]
        self.run(*args,image,env=env)
        self.names.append(name)
        return name

    def wait(self,check,description,seconds=90):
        deadline=time.monotonic()+seconds
        while True:
            try:
                if check(): return
            except (RuntimeError,URLError,ConnectionError,TimeoutError): pass
            if time.monotonic()>deadline:
                logs={name:self.run("docker","logs",name) for name in self.names}
                for name,text in logs.items():
                    for value in self.secrets: text=text.replace(value,"[redacted]")
                    logs[name]=text
                (ROOT/"target"/(self.prefix+"-diagnostics.json")).write_text(json.dumps(logs),encoding="utf-8")
                raise RuntimeError("Timed out waiting for "+description)
            time.sleep(1)

    def sql(self,text,env=None):
        args=["docker","exec","-i"]
        for key in env or {}: args += ["-e",key]
        return self.run(*args,self.pg,"psql","-U","postgres","-d","di_lucca",
                        "-v","ON_ERROR_STOP=1",env=env,stdin=text)

    def migrate(self,source,schema,password):
        self.run("docker","run","--rm","--network",self.prefix,"--mount",
                 f"type=bind,source={source},target=/workspace,readonly","-e","LIQUIBASE_COMMAND_PASSWORD",
                 "liquibase/liquibase:4.31.1","--search-path=/workspace",
                 "--changelog-file=changelog/changelog-master.yaml",
                 "--url=jdbc:postgresql://dlc-postgres:5432/di_lucca","--username="+schema+"_migrator",
                 "--default-schema-name="+schema,"--liquibase-schema-name="+schema,"update",
                 env={"LIQUIBASE_COMMAND_PASSWORD":password})

    def start(self):
        iam_db=Path(os.environ["IAM_DB_SOURCE"]).resolve()
        billing_db=Path(os.environ["BILLING_DB_SOURCE"]).resolve()
        self.run("docker","network","create",self.prefix)
        self.network_created=True
        self.pg=self.container("dlc-postgres","postgres:16-alpine",
            {"POSTGRES_DB":"di_lucca","POSTGRES_USER":"postgres","POSTGRES_PASSWORD":self.secrets[0]})
        self.wait(lambda: "accepting connections" in self.run("docker","exec",self.pg,"pg_isready","-h","127.0.0.1","-U","postgres"),"PostgreSQL")
        self.before_bootstrap()
        self.sql((iam_db/"deploy/bootstrap.sql").read_text())
        self.sql((billing_db/"deploy/bootstrap.sql").read_text())
        self.sql("CREATE EXTENSION IF NOT EXISTS pgcrypto;")
        for index,role in enumerate(("iam_migrator","iam_runtime","billing_migrator","billing_runtime"),1):
            self.sql("\\getenv provisioned PROVISION_PASSWORD\nALTER ROLE "+role+" PASSWORD :'provisioned';",
                     {"PROVISION_PASSWORD":self.secrets[index]})
        self.migrate(iam_db,"iam",self.secrets[1])
        self.migrate(billing_db,"billing",self.secrets[3])
        self.sql("DO $$ BEGIN IF has_schema_privilege('iam_runtime','billing','USAGE') OR "
                 "has_schema_privilege('billing_runtime','iam','USAGE') THEN "
                 "RAISE EXCEPTION 'Owner schema isolation violated'; END IF; END $$;")
        self.seed()
        broker=self.container("broker","rabbitmq:4.1-alpine",
            {"RABBITMQ_DEFAULT_USER":"integration","RABBITMQ_DEFAULT_PASS":self.secrets[5]})
        self.wait(lambda:self.run("docker","exec","-u","rabbitmq",broker,"rabbitmq-diagnostics","-q","ping") is not None,"RabbitMQ")
        self.run("java",str(ROOT/"tests/integration/TestServiceIdentity.java"),str(self.directory))
        private=(self.directory/"staff-private.pem").read_text()
        public=(self.directory/"staff-public.pem").read_text()
        self.secrets += [private,(self.directory/"billing/service-token").read_text()]
        self.container("service-keys",NGINX,mounts=[(self.directory/"public","/usr/share/nginx/html")])
        self.iam=self.container("auth-service",os.environ["IAM_RUNTIME_IMAGE"],dict(
            IAM_DATABASE_URL="jdbc:postgresql://dlc-postgres:5432/di_lucca",IAM_DATABASE_USER="iam_runtime",
            IAM_DATABASE_PASSWORD=self.secrets[2],IAM_JWT_PRIVATE_KEY_PEM=private,IAM_JWT_PUBLIC_KEY_PEM=public,
            IAM_JWT_KID="integration-staff",IAM_JWT_ISSUER="integration-staff-issuer",IAM_JWT_AUDIENCE="integration-staff",
            IAM_INTERNAL_SERVICE_JWKS_URI="http://service-keys/service-jwks.json",
            IAM_INTERNAL_SERVICE_ISSUER="integration-service-authority",IAM_INTERNAL_SERVICE_AUDIENCE="integration-iam",
            IAM_AUTH_ALLOWED_ORIGIN=self.origin,IAM_TRUSTED_GATEWAY_IP="127.0.0.1",
            IAM_EMAIL_SMTP_HOST="127.0.0.1",IAM_EMAIL_SMTP_PORT="9",IAM_EMAIL_SMTP_USER="unconfigured",
            IAM_EMAIL_SMTP_PASSWORD=self.secrets[6],IAM_EMAIL_FROM="integration@localhost.invalid",
            IAM_RABBITMQ_HOST="broker",IAM_RABBITMQ_USER="integration",IAM_RABBITMQ_PASSWORD=self.secrets[5],
            **{key:base64.b64encode(secrets.token_bytes(32)).decode() for key in
               ("IAM_REGISTRATION_PROOF_KEY_BASE64","IAM_LOGIN_IP_DIGEST_KEY_BASE64","IAM_CSRF_KEY_BASE64",
                "IAM_IDENTITY_EMAIL_LIMIT_KEY_BASE64")}))
        self.wait_iam()
        self.before_billing()
        dependency_env,dependency_mounts=self.billing_dependencies()
        owner=self.container("billing-service",os.environ["BILLING_RUNTIME_IMAGE"],dict(
            SPRING_DATASOURCE_URL="jdbc:postgresql://dlc-postgres:5432/di_lucca",
            SPRING_DATASOURCE_USERNAME="billing_runtime",SPRING_DATASOURCE_PASSWORD=self.secrets[4],
            BILLING_IAM_JWKS_URI="http://auth-service:8080/api/v1/auth/jwks",
            IAM_JWT_ISSUER="integration-staff-issuer",IAM_JWT_AUDIENCE="integration-staff",
            BILLING_IAM_ORIGIN="http://auth-service:8080",BILLING_IAM_LOCAL_DEVELOPMENT="true",
            BILLING_IAM_SERVICE_TOKEN_FILE="/run/billing-iam/service-token",**dependency_env),
            mounts=[(self.directory/"billing","/run/billing-iam"),*dependency_mounts])
        self.wait(lambda: "Started BillingApplication" in self.run("docker","logs",owner),"Billing")
        self.gateway=self.container("api-gateway",os.environ["GATEWAY_RUNTIME_IMAGE"],publish=True)
        self.base="http://"+self.run("docker","port",self.gateway,"8080/tcp").strip()
        self.wait(lambda:self.request("GET","/health")[0]==200,"Gateway")

    def before_bootstrap(self): pass
    def before_billing(self): pass
    def billing_dependencies(self): return {},[]

    def seed(self):
        self.users={role:role.lower()+"@integration.invalid" for role in
                    ("ADMINISTRATOR","DENTIST","SECRETARY_ASSISTANT")}
        for role,email in self.users.items():
            identity=str(uuid4())
            self.sql("\\getenv seed_password SEED_PASSWORD\n"+f"""
                INSERT INTO iam.users(user_id,email,display_name,password_hash,status,email_verified_at,created_at,updated_at)
                VALUES ('{identity}','{email}','Integration fixture',crypt(:'seed_password',gen_salt('bf',12)),
                        'ACTIVE',now(),now(),now());
                INSERT INTO iam.user_roles(user_id,role,assigned_by,assigned_at)
                VALUES ('{identity}','{role}','{identity}',now());
            """,{"SEED_PASSWORD":self.password})

    def request(self,method,path,body=None,token=None,key=None,cookie=None,proof=None):
        headers={"Origin":self.origin,"X-Correlation-Id":str(uuid4()),"Sec-Fetch-Site":"same-origin"}
        if token: headers["Authorization"]="Bearer "+token
        if key: headers["Idempotency-Key"]=key
        if cookie: headers["Cookie"]=cookie
        if proof: headers["X-CSRF-Token"]=proof
        if body is not None: headers["Content-Type"]="application/json"
        request=Request(self.base+path,headers=headers,method=method,
                        data=None if body is None else json.dumps(body).encode())
        try:
            with urlopen(request,timeout=12) as response:
                return response.status,dict(response.headers),response.read()
        except HTTPError as response:
            with response: return response.code,dict(response.headers),response.read()

    def login(self,role):
        status,headers,body=self.request("POST","/api/v1/auth/login",{"email":self.users[role],"password":self.password})
        if status!=200: raise AssertionError("Real IAM login returned "+str(status))
        token=json.loads(body)["accessToken"]
        cookie=headers["Set-Cookie"].split(";",1)[0]
        self.secrets += [token,cookie]
        return token,cookie

    def logout(self,cookie):
        status,_,body=self.request("GET","/api/v1/auth/csrf",cookie=cookie)
        if status!=200: raise AssertionError("CSRF acquisition returned "+str(status))
        proof=json.loads(body)["csrfToken"]
        self.secrets.append(proof)
        if self.request("POST","/api/v1/auth/logout",{},cookie=cookie,proof=proof)[0]!=204:
            raise AssertionError("Real IAM logout failed")

    def wait_iam(self):
        if hasattr(self,"base"):
            self.wait(lambda:self.request("GET","/api/v1/auth/jwks")[0]==200,"IAM HTTP after restart")
        else:
            self.wait(lambda:"Started IamApplication" in self.run("docker","logs",self.iam),"IAM")
    def stop_iam(self): self.run("docker","stop",self.iam)
    def start_iam(self): self.run("docker","start",self.iam); self.wait_iam()

    def close(self):
        for name in reversed(self.names):
            if not name.startswith(self.prefix+"-"): raise RuntimeError("Foreign cleanup target")
            self.run("docker","stop",name)
            self.run("docker","rm","-v",name)
        if self.network_created: self.run("docker","network","rm",self.prefix)
        if self.directory.resolve().parent!=(ROOT/"target").resolve(): raise RuntimeError("Foreign secret directory")
        self.temporary.cleanup()
