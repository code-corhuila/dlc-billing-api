"""Real owner read integration; synthetic stored snapshots are not clinical events."""
import json
import unittest
from uuid import uuid4
from shell_platform import ShellPlatform


class BillableItemReadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p = ShellPlatform()
        cls.addClassCleanup(cls.p.close)
        cls.p.start()

    def test_filters_pagination_roles_and_private_projection(self):
        p = self.p
        patients, procedures = [str(uuid4()) for _ in range(2)], [str(uuid4()) for _ in range(2)]
        ids = []
        for index, (patient, procedure) in enumerate(((0, 0), (0, 1), (1, 1))):
            account, appointment, item = [str(uuid4()) for _ in range(3)]
            ids.append(item)
            p.sql(f"""BEGIN;
                SET LOCAL ROLE billing_runtime;
                INSERT INTO billing.encounter_accounts
                (encounter_account_id,clinical_record_id,consultation_id,appointment_id,patient_id)
                VALUES ('{account}','{uuid4()}','{uuid4()}','{appointment}','{patients[patient]}');
                INSERT INTO billing.billable_items
                (item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,
                 item_type,description,quantity,unit_price,created_by,justification,created_at)
                VALUES ('{item}','{account}','{patients[patient]}','{appointment}','{procedures[procedure]}',
                '{uuid4()}','ADDITIONAL_MATERIAL','Financial test concept',0.5000,0.01,'{uuid4()}',
                'Protected clinical fixture','2026-11-06T00:00:00Z'); COMMIT;""")
        token, cookie = p.login("ADMINISTRATOR")
        path = "/api/v1/billable-items"
        for page, identity in enumerate(sorted(ids), 1):
            status, _, raw = p.request("GET", path+f"?page={page}&limit=1", token=token)
            self.assertEqual(200, status)
            response = json.loads(raw)
            self.assertEqual(identity, response["data"][0]["id"])
            self.assertEqual(3, response["meta"]["total"])
            self.assertEqual(3, response["meta"]["totalPages"])
            self.assertEqual("0.01", response["data"][0]["unitPrice"])
            self.assertEqual("0.5000", response["data"][0]["quantity"])
            self.assertEqual({"id", "patientId", "procedureId", "description", "quantity", "unitPrice",
                "currency", "approvalRequired", "createdAt", "updatedAt", "version", "status"},
                set(response["data"][0]))
        for query, total in ((f"patientId={patients[0]}", 2), (f"procedureId={procedures[1]}", 2),
                (f"patientId={patients[0]}&procedureId={procedures[1]}", 1),
                (f"patientId={uuid4()}", 0)):
            status, _, raw = p.request("GET", path+"?"+query, token=token)
            self.assertEqual(200, status)
            self.assertEqual(total, json.loads(raw)["meta"]["total"])
            self.assertEqual(total, len(json.loads(raw)["data"]))
        self.assertEqual(400, p.request("GET", path+"?patientId=1-1-1-1-1", token=token)[0])
        status, _, raw = p.request("GET", path+"?page=100", token=token)
        self.assertEqual(200, status)
        self.assertEqual([], json.loads(raw)["data"])
        self.assertEqual(3, json.loads(raw)["meta"]["total"])
        assistant, _ = p.login("SECRETARY_ASSISTANT")
        self.assertEqual(200, p.request("GET", path, token=assistant)[0])
        dentist, _ = p.login("DENTIST")
        self.assertEqual(403, p.request("GET", path, token=dentist)[0])
        p.logout(cookie)
        self.assertEqual(401, p.request("GET", path, token=token)[0])
