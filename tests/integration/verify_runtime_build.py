"""Isolated build outputs; disposable PostgreSQL/RabbitMQ, never IDE target/classes."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
from integration_platform import Platform, ROOT


def main():
    p=Platform()
    evidence=ROOT/"target/runtime-build-evidence"
    evidence.mkdir(exist_ok=True)
    source=Path(os.environ["BILLING_DB_SOURCE"]).resolve()
    try:
        p.run("docker","network","create",p.prefix); p.network_created=True
        p.pg=p.container("dlc-postgres","postgres:16-alpine",dict(
            POSTGRES_DB="di_lucca",POSTGRES_USER="postgres",POSTGRES_PASSWORD=p.secrets[0]))
        p.wait(lambda:"accepting connections" in p.run("docker","exec",p.pg,"pg_isready","-h","127.0.0.1","-U","postgres"),"PostgreSQL")
        p.sql((source/"deploy/bootstrap.sql").read_text())
        for role,password in (("billing_migrator",p.secrets[1]),("billing_runtime",p.secrets[2])):
            p.sql("\\getenv password PROVISION_PASSWORD\nALTER ROLE "+role+" PASSWORD :'password';",{"PROVISION_PASSWORD":password})
        p.migrate(source,"billing",p.secrets[1])
        for name in ("runtime-permissions","billable-snapshots","invoice-storage","invoice-draft-permissions","invoice-issuance","outbox-deliveries","payments"):
            p.sql((source/"tests"/(name+".sql")).read_text())
        p.migrate(source,"billing",p.secrets[1])
        broker=p.container("broker","rabbitmq:4.1-alpine",dict(RABBITMQ_DEFAULT_USER="integration",RABBITMQ_DEFAULT_PASS=p.secrets[5]))
        p.wait(lambda:p.run("docker","exec","-u","rabbitmq",broker,"rabbitmq-diagnostics","-q","ping") is not None,"RabbitMQ")
        uri="amqp://integration:"+p.secrets[5]+"@broker:5672"; p.secrets.append(uri)
        env=dict(os.environ,BILLING_TEST_JDBC_URL="jdbc:postgresql://dlc-postgres:5432/di_lucca",
                 BILLING_TEST_DB_USER="billing_migrator",BILLING_TEST_DB_PASSWORD=p.secrets[1],
                 BILLING_TEST_RUNTIME_DB_PASSWORD=p.secrets[2],BILLING_TEST_RABBITMQ_URI=uri)
        args=["docker","run","--rm","--network",p.prefix,"--mount",f"type=bind,source={ROOT},target=/source,readonly",
              "--mount",f"type=bind,source={evidence},target=/output",
              "--mount",f"type=bind,source={Path.home()/'.m2'},target=/root/.m2,readonly"]
        for key in ("BILLING_TEST_JDBC_URL","BILLING_TEST_DB_USER","BILLING_TEST_DB_PASSWORD","BILLING_TEST_RUNTIME_DB_PASSWORD","BILLING_TEST_RABBITMQ_URI"):
            args += ["-e",key]
        script='''mkdir /workspace
cp /source/pom.xml /workspace/
cp -R /source/src /workspace/
cd /workspace
trap 'cp -R target/surefire-reports /output/ 2>/dev/null || true' EXIT
mvn -o "$@"
if [ -f target/dlc-billing-api-0.1.0-SNAPSHOT.jar ]; then
  cp target/dlc-billing-api-0.1.0-SNAPSHOT.jar /output/
fi
'''
        args += ["maven:3.9-eclipse-temurin-21","sh","-eu","-c",script,"billing-build",*(sys.argv[1:] or ["verify"])]
        result=subprocess.run(args,env=env,capture_output=True,text=True,timeout=600)
        output=result.stdout+"\n"+result.stderr
        for secret in p.secrets: output=output.replace(secret,"[redacted]")
        (evidence/"maven.log").write_text(output,encoding="utf-8")
        print(output[-2200:])
        if result.returncode: raise RuntimeError("Isolated build failed; inspect redacted evidence")
        if not sys.argv[1:]: shutil.copyfile(evidence/"dlc-billing-api-0.1.0-SNAPSHOT.jar",ROOT/"target/dlc-billing-api-0.1.0-SNAPSHOT.jar")
    finally:
        p.close()


if __name__=="__main__": main()
