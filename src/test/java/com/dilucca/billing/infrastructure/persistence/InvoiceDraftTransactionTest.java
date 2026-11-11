package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.domain.invoicing.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD", matches=".+")
class InvoiceDraftTransactionTest {
    private final UUID patient=UUID.randomUUID(), appointment=UUID.randomUUID(), account=UUID.randomUUID(), actor=UUID.randomUUID();
    private final List<UUID> items=List.of(UUID.randomUUID(),UUID.randomUUID());
    private final JsonMapper mapper=JsonMapper.builder().build();
    private JdbcTemplate owner, runtime;

    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                "billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
        owner.update("INSERT INTO billing.encounter_accounts(encounter_account_id,clinical_record_id,consultation_id,"
                +"appointment_id,patient_id,status) VALUES (?,?,?,?,?,'CHARGES_COMPLETED')",account,UUID.randomUUID(),
                UUID.randomUUID(),appointment,patient);
        for (UUID id:items) owner.update("""
                INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,
                source_record_id,item_type,description,quantity,unit_price,created_by)
                VALUES (?,?,?,?,?,?,'ADDITIONAL_MATERIAL','Synthetic draft source',0.5000,0.01,?)
                """,id,account,patient,appointment,UUID.randomUUID(),UUID.randomUUID(),actor);
    }
    @AfterEach void clean() {
        owner.update("DELETE FROM billing.idempotency_records WHERE actor_id=?",actor.toString());
        owner.update("DELETE FROM billing.invoice_appointment_validations WHERE invoice_id IN (SELECT invoice_id FROM billing.invoices WHERE created_by=?)",actor);
        owner.update("DELETE FROM billing.invoice_items WHERE invoice_id IN (SELECT invoice_id FROM billing.invoices WHERE created_by=?)",actor);
        owner.update("DELETE FROM billing.invoices WHERE created_by=?",actor);
        owner.update("DELETE FROM billing.billable_items WHERE encounter_account_id=?",account);
        owner.update("DELETE FROM billing.encounter_accounts WHERE encounter_account_id=?",account);
    }
    private InvoiceDraft request() { return new InvoiceDraft(patient,null,items); }
    private JdbcInvoiceDraftCreation service(FinalizedAppointments appointments) {
        return new JdbcInvoiceDraftCreation(runtime,mapper,appointments);
    }

    @Test void roundsEachStoredLineAndReplaysWithoutAnotherAuthorityRead() throws Exception {
        AtomicInteger reads=new AtomicInteger();
        var service=service((id,p)->{assertEquals(appointment,id);assertEquals(patient,p);reads.incrementAndGet();return 7;});
        UUID key=UUID.randomUUID();
        var first=service.create(key,actor,request());
        assertEquals("0.02",mapper.readTree(first.body()).get("total").asString());
        assertEquals(appointment.toString(),mapper.readTree(first.body()).get("appointmentId").asString());
        assertEquals(first,service.create(key,actor,new InvoiceDraft(patient,null,List.of(items.get(1),items.get(0)))));
        assertEquals(1,reads.get());
        assertEquals(2L,owner.queryForObject("SELECT count(*) FROM billing.billable_items WHERE encounter_account_id=? AND status='BILLED' AND version=2",Long.class,account));
        assertEquals(7L,owner.queryForObject("SELECT appointment_version FROM billing.invoice_appointment_validations WHERE appointment_id=?",Long.class,appointment));
        assertThrows(InvoiceDraftConflict.class,()->service.create(key,actor,new InvoiceDraft(patient,appointment,items)));
    }

    @Test void rejectsMissingMixedAndAlreadyAllocatedSources() {
        var service=service((id,p)->7);
        assertThrows(InvoiceDraftConflict.class,()->service.create(UUID.randomUUID(),actor,new InvoiceDraft(UUID.randomUUID(),null,items)));
        assertThrows(InvoiceDraftConflict.class,()->service.create(UUID.randomUUID(),actor,new InvoiceDraft(patient,UUID.randomUUID(),items)));
        assertThrows(InvoiceDraftConflict.class,()->service.create(UUID.randomUUID(),actor,new InvoiceDraft(patient,null,List.of(UUID.randomUUID()))));
        service.create(UUID.randomUUID(),actor,request());
        assertThrows(InvoiceDraftConflict.class,()->service.create(UUID.randomUUID(),actor,request()));
    }

    @Test void rejectsChargesWhoseEncounterIsNotCompletedAndKeepsThemUnbilled() {
        owner.update("UPDATE billing.encounter_accounts SET status='DECLARED' WHERE encounter_account_id=?",account);
        AtomicInteger reads=new AtomicInteger();
        var service=service((id,p)->{reads.incrementAndGet();return 7;});
        assertEquals("ENCOUNTER_PRICING_PENDING",assertThrows(InvoiceDraftConflict.class,
                ()->service.create(UUID.randomUUID(),actor,request())).code());
        assertEquals(0,reads.get());
        assertEquals(2L,owner.queryForObject("SELECT count(*) FROM billing.billable_items WHERE encounter_account_id=? AND status='UNBILLED'",Long.class,account));
        assertEquals(0L,owner.queryForObject("SELECT count(*) FROM billing.invoices WHERE created_by=?",Long.class,actor));
    }

    @Test void rollsBackEveryWriteWhenAuthorityOrPersistenceFails() {
        var unavailable=service((id,p)->{throw new InvoiceDraftUnavailable();});
        assertThrows(InvoiceDraftUnavailable.class,()->unavailable.create(UUID.randomUUID(),actor,request()));
        owner.execute("""
                CREATE FUNCTION billing.reject_draft_probe() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic allocation failure'; END $$;
                CREATE TRIGGER reject_draft_probe BEFORE INSERT ON billing.invoice_items
                FOR EACH ROW WHEN (NEW.item_id='%s'::uuid) EXECUTE FUNCTION billing.reject_draft_probe();
                """.formatted(items.stream().max(Comparator.comparing(UUID::toString)).orElseThrow()));
        try { assertThrows(org.springframework.dao.DataAccessException.class,()->service((id,p)->7).create(UUID.randomUUID(),actor,request())); }
        finally { owner.execute("DROP TRIGGER reject_draft_probe ON billing.invoice_items; DROP FUNCTION billing.reject_draft_probe()"); }
        assertEquals(0L,owner.queryForObject("SELECT count(*) FROM billing.invoices WHERE created_by=?",Long.class,actor));
        assertEquals(2L,owner.queryForObject("SELECT count(*) FROM billing.billable_items WHERE encounter_account_id=? AND status='UNBILLED'",Long.class,account));
        assertEquals(0L,owner.queryForObject("SELECT count(*) FROM billing.idempotency_records WHERE actor_id=?",Long.class,actor.toString()));
    }

    @Test void competingKeysCanAllocateTheSameSourceOnlyOnce() throws Exception {
        var service=service((id,p)->7);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var outcomes=pool.invokeAll(List.of(
                ()->{try {service.create(UUID.randomUUID(),actor,request());return 201;}catch(InvoiceDraftConflict conflict){return 409;}},
                ()->{try {service.create(UUID.randomUUID(),actor,request());return 201;}catch(InvoiceDraftConflict conflict){return 409;}}));
            var statuses=new ArrayList<Integer>();
            for (var result:outcomes) statuses.add((Integer)result.get(10,TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(201,409),statuses);
        }
    }

    @Test void concurrentSameKeyReturnsOneStoredInvoice() throws Exception {
        AtomicInteger reads=new AtomicInteger();
        var service=service((id,p)->{reads.incrementAndGet();return 7;});
        UUID key=UUID.randomUUID();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->service.create(key,actor,request()));
            var second=pool.submit(()->service.create(key,actor,request()));
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,reads.get());
    }

    @Test void rejectsTotalOverflowBeforePersistingAnInvoice() {
        List<UUID> large=List.of(UUID.randomUUID(),UUID.randomUUID());
        for (UUID id:large) owner.update("""
                INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,
                source_record_id,item_type,description,quantity,unit_price,created_by)
                VALUES (?,?,?,?,?,?,'ADDITIONAL_MATERIAL','Synthetic overflow fixture',1,600000000000.00,?)
                """,id,account,patient,appointment,UUID.randomUUID(),UUID.randomUUID(),actor);
        assertThrows(InvoiceDraftConflict.class,()->service((id,p)->7).create(UUID.randomUUID(),actor,new InvoiceDraft(patient,null,large)));
        assertEquals(0L,owner.queryForObject("SELECT count(*) FROM billing.invoices WHERE created_by=?",Long.class,actor));
    }
}
