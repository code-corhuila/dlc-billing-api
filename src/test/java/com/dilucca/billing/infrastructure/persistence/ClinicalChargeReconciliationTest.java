package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class ClinicalChargeReconciliationTest {
    private JdbcTemplate owner,runtime;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final Map<String,UUID> ids=new HashMap<>();
    private final String code="RECON-"+UUID.randomUUID();
    private UUID id(String prefix,int n) { return ids.computeIfAbsent(prefix+":"+n,key -> UUID.randomUUID()); }
    @BeforeEach void connect() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),"billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
    }
    private String fixture(String name) throws Exception {
        String raw;
        try(var s=getClass().getResourceAsStream("/clinical/"+name+".json")) { raw=new String(s.readAllBytes(),StandardCharsets.UTF_8); }
        for(String prefix:List.of("81","82","83")) for(int n=1;n<=9;n++) raw=raw.replace(prefix+"000000-0000-4000-8000-"+String.format("%012d",n),id(prefix,n).toString());
        return raw.replace("RESTORATION",code).replaceAll("(?s)\"additionalItems\":\\[.*?\\]","\"additionalItems\":[]");
    }
    private void project(String name) throws Exception { new JdbcClinicalSourceProjection(runtime,mapper).ingest(fixture(name)); }
    private JdbcClinicalChargeReconciliation service() { return new JdbcClinicalChargeReconciliation(runtime,mapper); }
    private boolean reconcile() { return service().reconcile(id("82",2),id("82",3)); }
    private void freeze() { new JdbcEncounterWriteCoordinator(runtime,mapper).freeze(id("82",2),id("82",3),id("83",9)); }
    private void prices() {
        for(int version=1;version<=2;version++) owner.update("INSERT INTO billing.procedure_prices(price_id,procedure_code,name,base_price,valid_from,valid_to,version,created_by,created_at,updated_at) "
                +"VALUES (?,?,'Catalog snapshot',?,?::timestamptz,?::timestamptz,?,?,now(),now())",UUID.randomUUID(),code,
                new java.math.BigDecimal(version==1 ? "1.01" : "999.00"),version==1 ? "2026-01-01T00:00:00Z" : "2026-11-07T00:00:00Z",
                version==1 ? "2026-11-07T00:00:00Z" : null,version,id("81",9));
    }
    @AfterEach void clean() {
        for(UUID account:owner.queryForList("SELECT encounter_account_id FROM billing.encounter_accounts WHERE clinical_record_id=?",UUID.class,id("82",2))) {
            owner.update("DELETE FROM billing.procedure_charge_receipts WHERE encounter_account_id=?",account);
            owner.update("DELETE FROM billing.encounter_charge_receipts WHERE encounter_account_id=?",account);
            owner.update("DELETE FROM billing.outbox_events WHERE aggregate_id=?",account);
            owner.update("DELETE FROM billing.price_corrections WHERE item_id IN(SELECT item_id FROM billing.billable_items WHERE encounter_account_id=?)",account);
            owner.update("DELETE FROM billing.billable_items WHERE encounter_account_id=?",account);
            owner.update("DELETE FROM billing.encounter_accounts WHERE encounter_account_id=?",account);
        }
        owner.update("DELETE FROM billing.clinical_sources WHERE procedure_id=?",id("81",3));
        owner.update("DELETE FROM billing.clinical_procedures WHERE procedure_id=?",id("81",3));
        owner.update("DELETE FROM billing.clinical_declarations WHERE consultation_id=?",id("82",3));
        owner.update("DELETE FROM billing.clinical_consultations WHERE consultation_id=?",id("82",3));
        for(String prefix:List.of("81","82","83")) owner.update("DELETE FROM billing.clinical_inbox WHERE event_id=?",id(prefix,1));
        owner.update("DELETE FROM billing.encounter_freezes WHERE clinical_record_id=?",id("82",2));
        owner.update("DELETE FROM billing.encounter_write_gates WHERE clinical_record_id=?",id("82",2));
        owner.update("DELETE FROM billing.procedure_prices WHERE procedure_code=?",code);
        owner.update("DELETE FROM billing.additional_price_rules WHERE rule_code=?",code);
        owner.update("DELETE FROM billing.idempotency_records WHERE actor_id=?",id("81",9).toString());
    }
    @Test void usesCompletedAtAndPublishesOneProcedureAndEncounterFact() throws Exception {
        prices(); freeze(); project("declaration"); assertFalse(reconcile());
        project("procedure"); assertTrue(reconcile()); assertTrue(reconcile());
        assertEquals("1.01",owner.queryForObject("SELECT unit_price::text FROM billing.billable_items WHERE procedure_id=?",String.class,id("81",3)));
        assertEquals(1,owner.queryForObject("SELECT catalog_version FROM billing.billable_items WHERE procedure_id=?",Integer.class,id("81",3)));
        var events=owner.queryForList("SELECT event_body::text FROM billing.outbox_events WHERE aggregate_id IN(SELECT encounter_account_id FROM billing.encounter_accounts WHERE clinical_record_id=?) ORDER BY event_type",String.class,id("82",2));
        assertEquals(2,events.size());
        var first=mapper.readTree(events.getFirst()); assertEquals("EncounterAccount",first.get("aggregateType").stringValue());
        assertEquals("BillableItemsCreated",first.get("eventType").stringValue()); assertEquals(2,first.get("version").intValue());
        assertEquals("1.01",first.get("payload").get("total").stringValue());
        assertThrows(JdbcEncounterWriteCoordinator.Conflict.class,() -> new JdbcEncounterWriteCoordinator(runtime,mapper).release(id("82",2),id("82",3),id("83",9)));
    }
    @Test void missingPriceOrFreezeStaysPendingWithoutFictitiousAmounts() throws Exception {
        project("declaration"); project("procedure"); assertFalse(reconcile());
        freeze(); assertFalse(reconcile());
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.billable_items WHERE procedure_id=?",Integer.class,id("81",3)));
        prices(); assertTrue(reconcile());
    }
    @Test void concurrentReconciliationPublishesOnce() throws Exception {
        prices(); freeze(); project("procedure"); project("declaration");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(this::reconcile); var b=pool.submit(this::reconcile);
            assertTrue(a.get(10,TimeUnit.SECONDS)); assertTrue(b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM billing.procedure_charge_receipts WHERE procedure_id=?",Integer.class,id("81",3)));
    }
    @Test void outboxFailureRollsBackSnapshotsAndFinancialClosure() throws Exception {
        prices(); freeze(); project("procedure"); project("declaration");
        String trigger="fail_clinical_"+id("82",2).toString().replace("-","");
        owner.execute("CREATE FUNCTION billing."+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='BillableItemsCreated' AND NEW.event_body->'payload'->>'patientId'='"+id("81",4)+"' THEN RAISE EXCEPTION 'Synthetic outbox failure'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON billing.outbox_events FOR EACH ROW EXECUTE FUNCTION billing."+trigger+"()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,this::reconcile);
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.billable_items WHERE procedure_id=?",Integer.class,id("81",3)));
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.encounter_accounts WHERE clinical_record_id=?",Integer.class,id("82",2)));
        } finally { owner.execute("DROP TRIGGER "+trigger+" ON billing.outbox_events"); owner.execute("DROP FUNCTION billing."+trigger+"()"); }
        assertTrue(reconcile());
    }
    @Test void eventHandlerProjectsReorderedFactsAndResolvesWithoutDuplicateMoneyEvents() throws Exception {
        var worker=new JdbcClinicalEventHandling(runtime,mapper); prices(); freeze();
        worker.receive(fixture("declaration")); worker.receive(fixture("procedure"));
        worker.receive(fixture("consultation")); worker.receive(fixture("procedure"));
        assertTrue(reconcile());
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM billing.outbox_events WHERE aggregate_id IN(SELECT encounter_account_id FROM billing.encounter_accounts WHERE clinical_record_id=?)",Integer.class,id("82",2)));
    }
    @Test void sweepResolvesAfterFreezeAndCatalogBecomeAvailableWithoutAnotherClinicalEvent() throws Exception {
        var worker=new JdbcClinicalEventHandling(runtime,mapper);
        worker.receive(fixture("procedure")); worker.receive(fixture("declaration"));
        assertFalse(reconcile()); freeze(); prices();
        assertEquals(1,worker.sweep(1)); assertTrue(reconcile());
        assertEquals(0,worker.sweep(1));
    }
    @Test void approvalRuleProducesProposedSnapshotButNoFinancialCompletionUntilCorrection() throws Exception {
        prices(); freeze();
        owner.update("INSERT INTO billing.additional_price_rules(rule_id,rule_code,item_type,unit_price,requires_approval,valid_from,version,created_by,created_at,updated_at) VALUES (?,?,'ADDITIONAL_MATERIAL',10.00,TRUE,'2026-01-01',1,?,now(),now())",
                UUID.randomUUID(),code,id("81",9));
        String extra="{\"type\":\"ADDITIONAL_MATERIAL\",\"code\":\""+code+"\",\"quantity\":\"0.5000\",\"clinicalReason\":\"Protected fixture\",\"sourceRecordId\":\""+id("81",6)+"\"}";
        new JdbcClinicalSourceProjection(runtime,mapper).ingest(fixture("procedure").replace("\"additionalItems\":[]","\"additionalItems\":["+extra+"]"));
        project("declaration"); assertFalse(reconcile());
        assertEquals(Boolean.TRUE,owner.queryForObject("SELECT approval_required FROM billing.billable_items WHERE source_record_id=?",Boolean.class,id("81",6)));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.procedure_charge_receipts WHERE procedure_id=?",Integer.class,id("81",3)));
        new JdbcPriceCorrectionRecording(runtime,mapper).record(UUID.randomUUID(),id("81",9),id("81",6),
                new com.dilucca.billing.api.items.PriceCorrectionInput(new java.math.BigDecimal("37.11"),"Administrative correction",1));
        assertTrue(reconcile()); assertTrue(reconcile());
        assertEquals("19.57",owner.queryForObject("SELECT event_body->'payload'->>'total' FROM billing.outbox_events WHERE event_type='EncounterChargesCompleted' AND aggregate_id IN(SELECT encounter_account_id FROM billing.encounter_accounts WHERE clinical_record_id=?)",String.class,id("82",2)));
    }
}
