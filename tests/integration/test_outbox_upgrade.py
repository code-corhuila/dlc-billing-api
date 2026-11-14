"""Upgrade real committed financial intent from the pre-relay owner schema."""
import os
from pathlib import Path
import unittest
from integration_platform import Platform


class OutboxUpgradeTest(unittest.TestCase):
    def test_existing_event_is_scheduled_once_without_changing_body(self):
        p=Platform(); self.addCleanup(p.close)
        current=Path(os.environ["BILLING_DB_SOURCE"]).resolve()
        baseline=Path(os.environ["BILLING_BASELINE_DB_SOURCE"]).resolve()
        p.run("docker","network","create",p.prefix); p.network_created=True
        p.pg=p.container("dlc-postgres","postgres:16-alpine",dict(
            POSTGRES_DB="di_lucca",POSTGRES_USER="postgres",POSTGRES_PASSWORD=p.secrets[0]))
        p.wait(lambda:"accepting connections" in p.run("docker","exec",p.pg,"pg_isready","-h","127.0.0.1","-U","postgres"),"PostgreSQL")
        p.sql((baseline/"deploy/bootstrap.sql").read_text())
        p.sql("\\getenv password PROVISION_PASSWORD\nALTER ROLE billing_migrator PASSWORD :'password';",{"PROVISION_PASSWORD":p.secrets[1]})
        p.migrate(baseline,"billing",p.secrets[1])
        p.sql("""INSERT INTO billing.outbox_events(event_id,aggregate_id,event_type,event_version,occurred_at,event_body)
            VALUES ('73000000-0000-4000-8000-000000000001','73000000-0000-4000-8000-000000000002',
            'InvoiceIssued',1,now(),'{"eventId":"73000000-0000-4000-8000-000000000001",
            "aggregateId":"73000000-0000-4000-8000-000000000002","eventType":"InvoiceIssued",
            "version":1,"producer":"billing-service","payload":{},"metadata":{}}');""")
        query="SELECT event_body::text FROM billing.outbox_events;"
        before=p.sql(query)
        p.migrate(current,"billing",p.secrets[1])
        p.migrate(current,"billing",p.secrets[1])
        self.assertEqual(before,p.sql(query))
        p.sql("""DO $$ BEGIN IF (SELECT count(*) FROM billing.outbox_deliveries WHERE
            event_id='73000000-0000-4000-8000-000000000001' AND status='PENDING' AND attempts=0)<>1
            THEN RAISE EXCEPTION 'Legacy intent not scheduled exactly once'; END IF; END $$;""")
