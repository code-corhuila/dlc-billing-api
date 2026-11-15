package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.domain.invoicing.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class InvoiceIssuanceTransactionTest {
    private final UUID invoice=UUID.randomUUID(),patient=UUID.randomUUID(),appointment=UUID.randomUUID(),
            account=UUID.randomUUID(),item=UUID.randomUUID(),actor=UUID.randomUUID(),procedure=UUID.randomUUID();
    private JdbcTemplate owner,runtime;
    private final JsonMapper mapper=JsonMapper.builder().build();

    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                "billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
        owner.update("INSERT INTO billing.encounter_accounts(encounter_account_id,clinical_record_id,consultation_id,"
                +"appointment_id,patient_id,status,declared_procedure_ids) VALUES (?,?,?,?,?,'CHARGES_COMPLETED',ARRAY[?]::uuid[])",
                account,UUID.randomUUID(),UUID.randomUUID(),appointment,patient,procedure);
        owner.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,"
                +"source_record_id,item_type,description,quantity,unit_price,created_by,status,catalog_version) "
                +"VALUES (?,?,?,?,?,?,'BASE_PROCEDURE','Synthetic catalog source',0.5000,0.01,?,'BILLED',1)",
                item,account,patient,appointment,procedure,UUID.randomUUID(),actor);
        owner.update("INSERT INTO billing.invoices(invoice_id,patient_id,appointment_id,total,balance,created_by) VALUES (?,?,?,0.01,0.01,?)",
                invoice,patient,appointment,actor);
        owner.update("INSERT INTO billing.invoice_items(invoice_id,patient_id,item_id) VALUES (?,?,?)",invoice,patient,item);
        owner.update("INSERT INTO billing.invoice_appointment_validations VALUES (?,?,?,?,now())",invoice,appointment,patient,7L);
    }
    @AfterEach void clean() {
        owner.update("DELETE FROM billing.idempotency_records WHERE actor_id=?",actor.toString());
        owner.update("DELETE FROM billing.invoice_issuances WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.outbox_events WHERE aggregate_id=?",invoice);
        owner.update("DELETE FROM billing.invoice_appointment_validations WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.invoice_items WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.invoices WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.price_corrections WHERE item_id=?",item);
        owner.update("DELETE FROM billing.billable_items WHERE item_id=?",item);
        owner.update("DELETE FROM billing.encounter_accounts WHERE encounter_account_id=?",account);
    }
    private JdbcInvoiceIssuance service() { return new JdbcInvoiceIssuance(runtime,mapper); }
    private com.dilucca.billing.api.pricing.CreatedResource issue(UUID key,long version) {
        return service().issue(key,actor,invoice,version,UUID.randomUUID());
    }

    @Test void commitsIssuedIdentityHistoryEventAndExactReplayWithoutBroker() {
        UUID key=UUID.randomUUID();
        var result=issue(key,1);
        assertEquals(200,result.status());
        var body=mapper.readTree(result.body());
        assertEquals("ISSUED",body.get("status").stringValue());
        assertEquals("0.01",body.get("total").stringValue());
        assertEquals(2,body.get("version").intValue());
        assertTrue(body.get("invoiceNumber").stringValue().startsWith("BIL-"));
        assertEquals(result,issue(key,1));
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(key,2));
        var event=mapper.readTree(owner.queryForObject("SELECT event_body::text FROM billing.outbox_events WHERE aggregate_id=?",String.class,invoice));
        assertEquals("InvoiceIssued",event.get("eventType").stringValue());
        assertEquals("billing-service",event.get("producer").stringValue());
        assertEquals("0.01",event.get("payload").get("total").stringValue());
        assertEquals(body.get("issuedAt"),event.get("payload").get("issuedAt"));
        assertEquals(key.toString(),event.get("metadata").get("causationId").stringValue());
        assertEquals(body,mapper.readTree(owner.queryForObject("SELECT invoice_snapshot::text FROM billing.invoice_issuances WHERE invoice_id=?",String.class,invoice)));
        assertFalse(result.body().contains("justification"));
    }

    @Test void rejectsMissingStaleEmptyOrUndeclaredSources() {
        assertThrows(InvoiceIssuanceMissing.class,() -> service().issue(UUID.randomUUID(),actor,UUID.randomUUID(),1,UUID.randomUUID()));
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),2));
        owner.update("UPDATE billing.encounter_accounts SET status='DECLARED' WHERE encounter_account_id=?",account);
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
        owner.update("UPDATE billing.encounter_accounts SET status='CHARGES_COMPLETED',declared_procedure_ids='{}' WHERE encounter_account_id=?",account);
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
        owner.update("DELETE FROM billing.invoice_items WHERE invoice_id=?",invoice);
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.outbox_events WHERE aggregate_id=?",Integer.class,invoice));
    }

    @Test void requiresTerminalOwnerEvidence() {
        owner.update("DELETE FROM billing.invoice_appointment_validations WHERE invoice_id=?",invoice);
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
    }

    @Test void pendingCatalogPriceCannotBeIssuedUntilAdministrativeCorrection() {
        owner.update("DELETE FROM billing.invoice_items WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.billable_items WHERE item_id=?",item);
        owner.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,item_type,description,quantity,unit_price,created_by,status,catalog_version,approval_required) VALUES (?,?,?,?,?,?,'BASE_PROCEDURE','Synthetic pending catalog source',1,0.01,?,'BILLED',1,TRUE)",
                item,account,patient,appointment,procedure,UUID.randomUUID(),actor);
        owner.update("INSERT INTO billing.invoice_items VALUES (?,?,?)",invoice,patient,item);
        assertThrows(InvoiceIssuanceConflict.class,()->issue(UUID.randomUUID(),1));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.invoice_issuances WHERE invoice_id=?",Integer.class,invoice));
        new JdbcPriceCorrectionRecording(runtime,mapper).record(UUID.randomUUID(),actor,item,
                new com.dilucca.billing.api.items.PriceCorrectionInput(new java.math.BigDecimal("0.02"),"Administrative correction",1));
        assertEquals("ISSUED",mapper.readTree(issue(UUID.randomUUID(),2).body()).get("status").stringValue());
    }

    private void manualPrice(String role,String justification,String price) {
        owner.update("DELETE FROM billing.invoice_items WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.billable_items WHERE item_id=?",item);
        owner.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,"
                +"source_record_id,item_type,description,quantity,unit_price,created_by,status,pricing_authority_role,justification,approval_required) "
                +"VALUES (?,?,?,?,?,?,'BASE_PROCEDURE','Synthetic manual source',1,?,?,'BILLED',?,?,true)",
                item,account,patient,appointment,procedure,UUID.randomUUID(),new java.math.BigDecimal(price),actor,role,justification);
        owner.update("INSERT INTO billing.invoice_items VALUES (?,?,?)",invoice,patient,item);
    }

    @Test void acceptsJustifiedDentistPriceWithoutSecondApprovalAndRejectsUnprovenPrices() {
        manualPrice(null,"Reason without verified role","0.01");
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
        manualPrice("DENTIST"," ","0.01");
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
        manualPrice("DENTIST","Validated owner entry","0.02");
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
        manualPrice("DENTIST","Validated owner entry","0.01");
        assertEquals(200,issue(UUID.randomUUID(),1).status());
    }

    @Test void differentKeysCannotIssueTheSameDraftTwice() throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> attempt=() -> {
                try { return issue(UUID.randomUUID(),1).status(); }
                catch (InvoiceIssuanceConflict conflict) { return 409; }
            };
            var first=pool.submit(attempt); var second=pool.submit(attempt);
            assertEquals(Set.of(200,409),Set.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)));
        }
    }

    @Test void competingKeysIssueOnceAndSameKeyReplaysConcurrently() throws Exception {
        UUID key=UUID.randomUUID();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> issue(key,1));
            var second=pool.submit(() -> issue(key,1));
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertThrows(InvoiceIssuanceConflict.class,() -> issue(UUID.randomUUID(),1));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM billing.outbox_events WHERE aggregate_id=?",Integer.class,invoice));
    }

    @Test void lateOutboxFailureRollsBackInvoiceHistoryAndReplay() {
        lateFailure("outbox_events","NEW.aggregate_id='"+invoice+"'::uuid");
    }
    @Test void lateReplayFailureAlsoRollsBackStoredEventAndAudit() {
        lateFailure("idempotency_records","NEW.actor_id='"+actor+"'");
    }
    private void lateFailure(String table,String condition) {
        String trigger="fail_issuance_"+invoice.toString().replace("-","");
        owner.execute("CREATE FUNCTION billing."+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                +"IF "+condition+" THEN RAISE EXCEPTION 'test-only late failure'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON billing."+table+" FOR EACH ROW EXECUTE FUNCTION billing."+trigger+"()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,() -> issue(UUID.randomUUID(),1));
            assertEquals("DRAFT",owner.queryForObject("SELECT status FROM billing.invoices WHERE invoice_id=?",String.class,invoice));
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.invoice_issuances WHERE invoice_id=?",Integer.class,invoice));
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.outbox_events WHERE aggregate_id=?",Integer.class,invoice));
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.idempotency_records WHERE actor_id=?",Integer.class,actor.toString()));
        } finally {
            owner.execute("DROP TRIGGER "+trigger+" ON billing."+table);
            owner.execute("DROP FUNCTION billing."+trigger+"()");
        }
    }
}
