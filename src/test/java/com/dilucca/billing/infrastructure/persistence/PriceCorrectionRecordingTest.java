package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.api.items.PriceCorrectionInput;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class PriceCorrectionRecordingTest {
    JdbcTemplate owner,runtime;
    final UUID item=UUID.randomUUID(),account=UUID.randomUUID(),patient=UUID.randomUUID(),appointment=UUID.randomUUID(),actor=UUID.randomUUID();
    final JsonMapper mapper=JsonMapper.builder().build();
    @BeforeEach void setup() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),"billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
        owner.update("INSERT INTO billing.encounter_accounts(encounter_account_id,clinical_record_id,consultation_id,appointment_id,patient_id) VALUES (?,?,?,?,?)",
                account,UUID.randomUUID(),UUID.randomUUID(),appointment,patient);
        owner.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,item_type,description,quantity,unit_price,approval_required,created_by) VALUES (?,?,?,?,?,?,'ADDITIONAL_MATERIAL','Synthetic correction fixture',0.5,10.00,TRUE,?)",
                item,account,patient,appointment,UUID.randomUUID(),UUID.randomUUID(),actor);
    }
    JdbcPriceCorrectionRecording service() { return new JdbcPriceCorrectionRecording(runtime,mapper); }
    PriceCorrectionInput input() { return new PriceCorrectionInput(new BigDecimal("37.11"),"Protected administrative reason",1); }
    @AfterEach void clean() {
        owner.update("DELETE FROM billing.idempotency_records WHERE actor_id=?",actor.toString());
        owner.update("DELETE FROM billing.price_corrections WHERE item_id=?",item);
        owner.update("DELETE FROM billing.invoice_items WHERE item_id=?",item);
        owner.update("DELETE FROM billing.invoices WHERE patient_id=?",patient);
        owner.update("DELETE FROM billing.billable_items WHERE item_id=?",item);
        owner.update("DELETE FROM billing.encounter_accounts WHERE encounter_account_id=?",account);
    }
    @Test void atomicallyCorrectsAndReplaysOriginalResponseWithoutExposingReason() {
        UUID key=UUID.randomUUID(); var result=service().record(key,actor,item,input());
        assertEquals(200,result.status()); assertFalse(result.body().contains("Protected"));
        assertEquals("37.11",mapper.readTree(result.body()).get("unitPrice").stringValue());
        assertFalse(mapper.readTree(result.body()).get("approvalRequired").booleanValue());
        assertEquals("18.56",owner.queryForObject("SELECT line_total::text FROM billing.billable_items WHERE item_id=?",String.class,item));
        assertEquals(result,service().record(key,actor,item,input()));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM billing.price_corrections WHERE item_id=?",Integer.class,item));
        assertThrows(JdbcPriceCorrectionRecording.Conflict.class,()->service().record(key,actor,item,new PriceCorrectionInput(new BigDecimal("38.00"),input().reason(),1)));
        assertThrows(JdbcPriceCorrectionRecording.Conflict.class,()->service().record(UUID.randomUUID(),actor,item,input()));
    }
    @Test void missingAndStaleRequestsPersistNothing() {
        assertThrows(JdbcPriceCorrectionRecording.Missing.class,()->service().record(UUID.randomUUID(),actor,UUID.randomUUID(),input()));
        assertThrows(JdbcPriceCorrectionRecording.Conflict.class,()->service().record(UUID.randomUUID(),actor,item,new PriceCorrectionInput(input().unitPrice(),input().reason(),2)));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.price_corrections WHERE item_id=?",Integer.class,item));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.idempotency_records WHERE actor_id=?",Integer.class,actor.toString()));
    }
    @Test void neverReplacesAnAlreadyJustifiedLegacyManualCarePrice() {
        owner.update("DELETE FROM billing.billable_items WHERE item_id=?",item);
        owner.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,item_type,description,quantity,unit_price,approval_required,created_by,pricing_authority_role,justification) VALUES (?,?,?,?,?,?,'ADDITIONAL_MATERIAL','Synthetic legacy manual price',1,10.00,TRUE,?,'DENTIST','Validated care-time reason')",
                item,account,patient,appointment,UUID.randomUUID(),UUID.randomUUID(),actor);
        assertThrows(JdbcPriceCorrectionRecording.Conflict.class,()->service().record(UUID.randomUUID(),actor,item,input()));
        assertEquals("10.00",owner.queryForObject("SELECT unit_price::text FROM billing.billable_items WHERE item_id=?",String.class,item));
    }
    UUID allocate(String status) {
        UUID invoice=UUID.randomUUID();
        owner.update("INSERT INTO billing.invoices(invoice_id,patient_id,total,balance,created_by,status,invoice_number,issued_at) VALUES (?,?,5.00,5.00,?,?,?,?)",
                invoice,patient,actor,status,status.equals("DRAFT")?null:invoice.toString(),status.equals("DRAFT")?null:java.sql.Timestamp.from(java.time.Instant.now()));
        owner.update("INSERT INTO billing.invoice_items(invoice_id,patient_id,item_id) VALUES (?,?,?)",invoice,patient,item);
        owner.update("UPDATE billing.billable_items SET status='BILLED',version=version+1,updated_at=clock_timestamp() WHERE item_id=?",item);
        return invoice;
    }
    @Test void correctsUnissuedAllocatedItemAndRecomputesDraftTotals() {
        UUID invoice=allocate("DRAFT");
        service().record(UUID.randomUUID(),actor,item,new PriceCorrectionInput(input().unitPrice(),input().reason(),2));
        assertEquals("18.56",owner.queryForObject("SELECT total::text FROM billing.invoices WHERE invoice_id=?",String.class,invoice));
        assertEquals("18.56",owner.queryForObject("SELECT balance::text FROM billing.invoices WHERE invoice_id=?",String.class,invoice));
        assertEquals(2,owner.queryForObject("SELECT version FROM billing.invoices WHERE invoice_id=?",Integer.class,invoice));
    }
    @Test void refusesIssuedSnapshots() {
        allocate("ISSUED");
        assertThrows(JdbcPriceCorrectionRecording.Conflict.class,()->service().record(UUID.randomUUID(),actor,item,new PriceCorrectionInput(input().unitPrice(),input().reason(),2)));
        assertEquals("10.00",owner.queryForObject("SELECT unit_price::text FROM billing.billable_items WHERE item_id=?",String.class,item));
    }
    @Test void runtimeCannotBypassNarrowCorrectionCapabilityOrChangeAudit() {
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("UPDATE billing.billable_items SET unit_price=99 WHERE item_id=?",item));
        service().record(UUID.randomUUID(),actor,item,input());
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("DELETE FROM billing.price_corrections WHERE item_id=?",item));
        assertThrows(org.springframework.dao.DataAccessException.class,()->runtime.update("UPDATE billing.price_corrections SET reason='changed' WHERE item_id=?",item));
    }
    @Test void concurrentDistinctKeysCorrectOnlyOnce() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<Boolean> attempt=()->{gate.await();try {service().record(UUID.randomUUID(),actor,item,input());return true;}
                catch(JdbcPriceCorrectionRecording.Conflict conflict){return false;}};
            var a=pool.submit(attempt); var b=pool.submit(attempt); gate.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM billing.price_corrections WHERE item_id=?",Integer.class,item));
    }
    @Test void replayStorageFailureRollsBackCorrectionAndAudit() {
        var failing=new JdbcTemplate(runtime.getDataSource()) {
            @Override public int update(String sql,Object...args) {
                if(sql.contains("INSERT INTO billing.idempotency_records")) throw new org.springframework.dao.DataAccessResourceFailureException("Injected receipt failure");
                return super.update(sql,args);
            }
        };
        assertThrows(org.springframework.dao.DataAccessException.class,()->new JdbcPriceCorrectionRecording(failing,mapper).record(UUID.randomUUID(),actor,item,input()));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.price_corrections WHERE item_id=?",Integer.class,item));
        assertEquals("10.00",owner.queryForObject("SELECT unit_price::text FROM billing.billable_items WHERE item_id=?",String.class,item));
    }
}
