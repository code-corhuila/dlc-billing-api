"""Real Appointments owner; seeded terminal facts do not simulate Clinical closure."""
import os
from pathlib import Path
import secrets
from shell_platform import ShellPlatform


class AppointmentPlatform(ShellPlatform):
    def before_bootstrap(self):
        source=Path(os.environ["APPOINTMENTS_DB_SOURCE"]).resolve()
        self.sql((source/"deploy/bootstrap.sql").read_text())
        self.app_password=secrets.token_urlsafe(32)
        self.secrets.append(self.app_password)
        for role in ("appointments_migrator","appointments_runtime"):
            self.sql("\\getenv password PROVISION_PASSWORD\nALTER ROLE "+role+" PASSWORD :'password';",
                     {"PROVISION_PASSWORD":self.app_password})
        self.run("docker","run","--rm","--network",self.prefix,"--mount",
                 f"type=bind,source={source},target=/workspace,readonly","-e","LIQUIBASE_COMMAND_PASSWORD",
                 "liquibase/liquibase:4.31.1","--search-path=/workspace",
                 "--changelog-file=changelog/changelog-master.yaml",
                 "--url=jdbc:postgresql://dlc-postgres:5432/di_lucca","--username=appointments_migrator",
                 "--default-schema-name=appointments","--liquibase-schema-name=appointments_liquibase",
                 "update","--context-filter=qa",env={"LIQUIBASE_COMMAND_PASSWORD":self.app_password})
        self.sql((source/"deploy/bootstrap.sql").read_text())

    def before_billing(self):
        for path in ("billing-appointments/service-token","appointments/iam-token"):
            self.secrets.append((self.directory/path).read_text())
        self.appointments=self.container("appointments-api",os.environ["APPOINTMENTS_RUNTIME_IMAGE"],dict(
            DB_URL="jdbc:postgresql://dlc-postgres:5432/di_lucca",DB_USERNAME="appointments_runtime",
            DB_PASSWORD=self.app_password,JWT_PUBLIC_KEY_FILE="/run/keys/staff-public.pem",
            JWT_ISSUER="integration-staff-issuer",JWT_AUDIENCE="integration-staff",
            INTERNAL_SERVICE_JWT_PUBLIC_KEY_FILE="/run/keys/service-public.pem",
            INTERNAL_SERVICE_JWT_ISSUER="integration-service-authority",
            INTERNAL_SERVICE_JWT_AUDIENCE="integration-appointments",
            AUTH_INTERNAL_URL="http://auth-service:8080",AUTH_SERVICE_TOKEN_FILE="/run/service/iam-token",
            PATIENTS_INTERNAL_URL="http://patients-api:8080",APPOINTMENTS_RELAY_ENABLED="false",
            APPOINTMENTS_CONSUMERS_ENABLED="false",APPOINTMENTS_REMINDERS_ENABLED="false"),mounts=[
                (self.directory/"staff-public.pem","/run/keys/staff-public.pem"),
                (self.directory/"service-public.pem","/run/keys/service-public.pem"),
                (self.directory/"appointments","/run/service")])
        self.wait(lambda:"Started AppointmentsApplication" in self.run("docker","logs",self.appointments),
                  "Real Appointments")
        self.sql("DO $$ BEGIN IF has_schema_privilege('billing_runtime','appointments','USAGE') "
                 "OR has_schema_privilege('appointments_runtime','billing','USAGE') "
                 "OR has_database_privilege('appointments_migrator',current_database(),'CREATE') "
                 "THEN RAISE EXCEPTION 'Owner isolation violated'; END IF; END $$;")

    def billing_dependencies(self):
        return dict(BILLING_APPOINTMENTS_ORIGIN="http://appointments-api:8080",
                    BILLING_APPOINTMENTS_LOCAL_DEVELOPMENT="true",
                    BILLING_APPOINTMENTS_SERVICE_TOKEN_FILE="/run/billing-appointments/service-token"),[
                        (self.directory/"billing-appointments","/run/billing-appointments")]
