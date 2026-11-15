package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class EncounterWriteCoordinatorTest {
    private JdbcTemplate owner,runtime;
    private final UUID record=UUID.randomUUID(),consultation=UUID.randomUUID(),operation=UUID.randomUUID();
    @BeforeEach void connect() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),"billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
    }
    @AfterEach void clean() {
        owner.update("DELETE FROM billing.clinical_declarations WHERE clinical_record_id=?",record);
        owner.update("DELETE FROM billing.idempotency_records WHERE actor_id='service:clinical-test' AND resource_key LIKE ?","%"+record+"%");
        owner.update("DELETE FROM billing.manual_charge_admissions WHERE clinical_record_id=?",record);
        owner.update("DELETE FROM billing.encounter_freezes WHERE clinical_record_id=?",record);
        owner.update("DELETE FROM billing.encounter_write_gates WHERE clinical_record_id=?",record);
    }
    private JdbcEncounterWriteCoordinator service() { return new JdbcEncounterWriteCoordinator(runtime,JsonMapper.builder().build()); }
    @Test void freezesEmptyEncounterWithoutInventingFinancialAccountAndRetainsReleaseReplay() {
        var first=service().freeze(record,consultation,operation);
        assertTrue(first.settled()); assertEquals(0,first.pendingWrites()); assertEquals(List.of(),first.chargeIds());
        assertEquals("FROZEN",first.freezeState()); assertEquals(first,service().freeze(record,consultation,operation));
        var released=service().release(record,consultation,operation);
        assertEquals("OPEN",released.freezeState());
        assertEquals(released,service().release(record,consultation,operation));
        assertEquals(released,service().freeze(record,consultation,operation));
        UUID next=UUID.randomUUID(); service().freeze(record,consultation,next);
        assertEquals(released,service().release(record,consultation,operation));
        assertThrows(JdbcEncounterWriteCoordinator.Conflict.class,() -> service().freeze(record,consultation,UUID.randomUUID()));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.encounter_accounts WHERE clinical_record_id=?",Integer.class,record));
    }
    @Test void pendingOutcomeBlocksSettlementUntilExplicitResolutionAndCommittedFreezeNeverReleases() {
        UUID write=UUID.randomUUID(),charge=UUID.randomUUID();
        service().admit(record,consultation,write,charge);
        var first=service().freeze(record,consultation,operation);
        assertFalse(first.settled()); assertEquals(1,first.pendingWrites()); assertTrue(first.chargeIds().isEmpty());
        assertThrows(JdbcEncounterWriteCoordinator.Conflict.class,() -> service().admit(record,consultation,UUID.randomUUID(),UUID.randomUUID()));
        owner.update("UPDATE billing.manual_charge_admissions SET state='COMMITTED',resolved_at=now() WHERE operation_id=?",write);
        var settled=service().freeze(record,consultation,operation);
        assertTrue(settled.settled()); assertEquals(List.of(charge),settled.chargeIds());
        owner.update("UPDATE billing.encounter_freezes SET state='COMMITTED' WHERE clinical_record_id=? AND operation_id=?",record,operation);
        assertThrows(JdbcEncounterWriteCoordinator.Conflict.class,() -> service().release(record,consultation,operation));
    }
    @Test void concurrentAdmissionIsEitherCountedOrRejectedByTheFreeze() throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var admission=pool.submit(() -> { barrier.await(); try { service().admit(record,consultation,UUID.randomUUID(),UUID.randomUUID()); return true; }
                catch(JdbcEncounterWriteCoordinator.Conflict frozen) { return false; } });
            var freeze=pool.submit(() -> { barrier.await(); return service().freeze(record,consultation,operation); });
            boolean admitted=admission.get(10,TimeUnit.SECONDS);
            var result=freeze.get(10,TimeUnit.SECONDS);
            assertEquals(admitted ? 1 : 0,result.pendingWrites()); assertEquals(!admitted,result.settled());
        }
    }
    @Test void commandKeyBindsPayloadButRepeatsReadCurrentSettlement() {
        UUID key=UUID.randomUUID(),write=UUID.randomUUID();
        service().admit(record,consultation,write,UUID.randomUUID());
        assertFalse(service().freezeCommand(key,"clinical-test",record,consultation,operation).settled());
        owner.update("UPDATE billing.manual_charge_admissions SET state='ABORTED',resolved_at=now() WHERE operation_id=?",write);
        assertTrue(service().freezeCommand(key,"clinical-test",record,consultation,operation).settled());
        assertThrows(com.dilucca.billing.api.pricing.PriceCreationConflict.class,
                () -> service().freezeCommand(key,"clinical-test",record,consultation,UUID.randomUUID()));
    }
    @Test void committedClinicalDeclarationRejectsReleaseBeforeMoneyReconciliation() {
        service().freeze(record,consultation,operation);
        owner.update("INSERT INTO billing.clinical_declarations(clinical_record_id,consultation_id,patient_id,appointment_id,dentist_id,procedure_ids,manual_charge_ids,completed_at,clinical_version,fact_hash,financial_fact) "
                +"VALUES (?,?,?,?,?,'{}','{}',now(),1,repeat('a',64),'{}')",record,consultation,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        assertThrows(JdbcEncounterWriteCoordinator.Conflict.class,() -> service().release(record,consultation,operation));
    }
}
