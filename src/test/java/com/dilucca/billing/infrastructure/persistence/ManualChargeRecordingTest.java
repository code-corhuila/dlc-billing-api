package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.api.manual.ManualChargeInput;
import com.dilucca.billing.infrastructure.security.AppointmentFinalizationClient.Encounter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class ManualChargeRecordingTest {
    JdbcTemplate owner,runtime;
    final JsonMapper mapper=JsonMapper.builder().build();
    final Map<String,UUID> ids=new HashMap<>();
    final AtomicBoolean assigned=new AtomicBoolean(true);
    UUID id(String prefix,int n) { return ids.computeIfAbsent(prefix+n,k -> UUID.randomUUID()); }
    @BeforeEach void setup() throws Exception {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),"billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
        for(String name:List.of("procedure","consultation")) {
            String raw;
            try(var stream=getClass().getResourceAsStream("/clinical/"+name+".json")) { raw=new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
            for(String prefix:List.of("81","82")) for(int n=1;n<=9;n++) raw=raw.replace(prefix+"000000-0000-4000-8000-"+String.format("%012d",n),id(prefix,n).toString());
            new JdbcClinicalSourceProjection(runtime,mapper).ingest(raw);
        }
    }
    ManualChargeInput input() { return new ManualChargeInput(id("81",4),id("81",5),id("81",3),id("81",6),
            "ADDITIONAL_MATERIAL",new BigDecimal("0.5000"),new BigDecimal("37.11"),"COP","Exact manual concept","Protected owner reason"); }
    JdbcManualChargeRecording service() {
        return new JdbcManualChargeRecording(runtime,mapper,
                appointment -> new Encounter(appointment,id("81",4),id("81",9),"EN_ATENCION",7),
                (patient,dentist) -> assigned.get() && patient.equals(id("81",4)) && dentist.equals(id("81",9)));
    }
    com.dilucca.billing.api.pricing.CreatedResource record(UUID key) {
        return service().record(key,id("81",9),"DENTIST",input(),UUID.randomUUID());
    }
    int count(String table) { return owner.queryForObject("SELECT count(*) FROM billing."+table+" WHERE "+switch(table) {
        case "manual_charges","billable_items" -> "source_record_id=?";
        case "manual_charge_admissions" -> "clinical_record_id=?";
        default -> throw new IllegalArgumentException(); },Integer.class,table.equals("manual_charge_admissions") ? id("82",2) : id("81",6)); }
    @AfterEach void clean() {
        owner.update("DELETE FROM billing.idempotency_records WHERE actor_id=?",id("81",9).toString());
        owner.update("DELETE FROM billing.outbox_events WHERE aggregate_id IN(SELECT charge_id FROM billing.manual_charges WHERE source_record_id=?)",id("81",6));
        owner.update("DELETE FROM billing.manual_charge_admissions WHERE clinical_record_id=?",id("82",2));
        owner.update("DELETE FROM billing.manual_charges WHERE source_record_id=?",id("81",6));
        owner.update("DELETE FROM billing.billable_items WHERE source_record_id=?",id("81",6));
        owner.update("DELETE FROM billing.encounter_accounts WHERE clinical_record_id=?",id("82",2));
        owner.update("DELETE FROM billing.encounter_freezes WHERE clinical_record_id=?",id("82",2));
        owner.update("DELETE FROM billing.encounter_write_gates WHERE clinical_record_id=?",id("82",2));
        owner.update("DELETE FROM billing.clinical_sources WHERE procedure_id=?",id("81",3));
        owner.update("DELETE FROM billing.clinical_procedures WHERE procedure_id=?",id("81",3));
        owner.update("DELETE FROM billing.clinical_consultations WHERE consultation_id=?",id("82",3));
        for(String prefix:List.of("81","82")) owner.update("DELETE FROM billing.clinical_inbox WHERE event_id=?",id(prefix,1));
    }
    @Test void commitsExactSnapshotAdmissionAndMinimizedFactThenReplaysAfterFreeze() {
        UUID key=UUID.randomUUID(); var created=record(key); assertEquals(201,created.status());
        assertEquals(1,count("manual_charges")); assertEquals(1,count("billable_items")); assertEquals(1,count("manual_charge_admissions"));
        assertEquals("18.56",owner.queryForObject("SELECT line_total::text FROM billing.billable_items WHERE source_record_id=?",String.class,id("81",6)));
        assertEquals("37.11",mapper.readTree(created.body()).get("unitPrice").stringValue());
        var frozen=new JdbcEncounterWriteCoordinator(runtime,mapper).freeze(id("82",2),id("82",3),UUID.randomUUID());
        assertTrue(frozen.settled()); assertEquals(1,frozen.chargeIds().size()); assertEquals(created,record(key));
        String event=owner.queryForObject("SELECT event_body::text FROM billing.outbox_events WHERE aggregate_id=?",String.class,frozen.chargeIds().getFirst());
        assertFalse(event.contains("Protected owner reason")); assertFalse(event.contains("37.11"));
        assertEquals("ManualChargeRecorded",mapper.readTree(event).get("eventType").stringValue());
        assigned.set(false); assertThrows(JdbcManualChargeRecording.Missing.class,() -> record(key));
    }
    @Test void rejectsResolvedSourceAndFrozenEncounterWithoutPartialWrites() {
        new JdbcEncounterWriteCoordinator(runtime,mapper).freeze(id("82",2),id("82",3),UUID.randomUUID());
        assertThrows(JdbcManualChargeRecording.Conflict.class,() -> record(UUID.randomUUID()));
        assertEquals(0,count("manual_charges")); assertEquals(0,count("manual_charge_admissions"));
    }
    @Test void competingKeysCanResolveSourceOnlyOnce() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);
            Callable<Boolean> write=() -> { barrier.await(); try { record(UUID.randomUUID()); return true; }
                catch(JdbcManualChargeRecording.Conflict duplicate) { return false; } };
            var a=pool.submit(write); var b=pool.submit(write);
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count("manual_charges")); assertEquals(1,count("manual_charge_admissions"));
    }
    @Test void invalidCareStateNeverWritesMoney() {
        var unavailableCare=new JdbcManualChargeRecording(runtime,mapper,
                appointment -> new Encounter(appointment,id("81",4),id("81",9),"FINALIZADA",8),(p,d) -> true);
        assertThrows(JdbcManualChargeRecording.Conflict.class,() -> unavailableCare.record(UUID.randomUUID(),id("81",9),"DENTIST",input(),UUID.randomUUID()));
        assertEquals(0,count("billable_items")); assertEquals(0,count("manual_charge_admissions"));
    }
    @Test void lateOutboxFailureRollsBackMoneyAndAdmission() {
        String trigger="fail_manual_"+id("82",2).toString().replace("-","");
        owner.execute("CREATE FUNCTION billing."+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='ManualChargeRecorded' AND NEW.event_body->'payload'->>'patientId'='"+id("81",4)+"' THEN RAISE EXCEPTION 'Synthetic outbox failure'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON billing.outbox_events FOR EACH ROW EXECUTE FUNCTION billing."+trigger+"()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,() -> record(UUID.randomUUID()));
            assertEquals(0,count("manual_charges")); assertEquals(0,count("billable_items")); assertEquals(0,count("manual_charge_admissions"));
        } finally { owner.execute("DROP TRIGGER "+trigger+" ON billing.outbox_events"); owner.execute("DROP FUNCTION billing."+trigger+"()"); }
        assertEquals(201,record(UUID.randomUUID()).status());
    }
    @Test void protectedReadAllowsAdministratorAndSourceDentistWithCurrentAssignmentOnly() {
        UUID charge=UUID.fromString(mapper.readTree(record(UUID.randomUUID()).body()).get("id").stringValue());
        var query=new JdbcManualChargeQuery(runtime,(patient,dentist) -> assigned.get());
        assertEquals("37.11",query.find(charge,id("81",9),"DENTIST").get("unitPrice"));
        assigned.set(false);
        assertThrows(JdbcManualChargeRecording.Missing.class,() -> query.find(charge,id("81",9),"DENTIST"));
        assertEquals("Protected owner reason",query.find(charge,UUID.randomUUID(),"ADMINISTRATOR").get("justification"));
        assertThrows(JdbcManualChargeRecording.Missing.class,() -> query.find(charge,UUID.randomUUID(),"DENTIST"));
        assertThrows(JdbcManualChargeRecording.Missing.class,() -> query.find(UUID.randomUUID(),id("81",9),"DENTIST"));
        assertThrows(JdbcManualChargeRecording.Forbidden.class,() -> query.find(charge,id("81",9),"SECRETARY_ASSISTANT"));
    }
}
