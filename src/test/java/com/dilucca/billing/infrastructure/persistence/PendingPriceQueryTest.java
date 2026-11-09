package com.dilucca.billing.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** The pending-price read over the clinical projection, as billing_runtime (ADR-015 decision 6). */
@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD", matches=".+")
class PendingPriceQueryTest {
    private final UUID patient=UUID.randomUUID(), appointment=UUID.randomUUID(), record=UUID.randomUUID(),
            consultation=UUID.randomUUID(), procedure=UUID.randomUUID(), account=UUID.randomUUID(),
            priced=UUID.randomUUID(), missing=UUID.randomUUID(), actor=UUID.randomUUID();
    private final Instant completed=Instant.parse("2026-11-08T13:30:55Z");
    private JdbcTemplate owner, runtime;

    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                "billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
        String hash="a".repeat(64);
        owner.update("INSERT INTO billing.clinical_procedures(procedure_id,treatment_id,patient_id,appointment_id,"
                +"procedure_code,completed_at,fact_hash,financial_fact) VALUES (?,?,?,?,'D2391',?,?,'{}'::jsonb)",
                procedure,UUID.randomUUID(),patient,appointment,Timestamp.from(completed),hash);
        owner.update("INSERT INTO billing.clinical_sources(source_record_id,procedure_id,item_type,quantity,source_code)"
                +" VALUES (?,?,'BASE_PROCEDURE',1,'D2391'),(?,?,'ADDITIONAL_MATERIAL',2,'MAT-ADH')",
                priced,procedure,missing,procedure);
        owner.update("INSERT INTO billing.clinical_declarations(clinical_record_id,consultation_id,patient_id,"
                +"appointment_id,dentist_id,procedure_ids,manual_charge_ids,completed_at,clinical_version,fact_hash,"
                +"financial_fact) VALUES (?,?,?,?,?,ARRAY[?]::uuid[],'{}'::uuid[],?,1,?,'{}'::jsonb)",
                record,consultation,patient,appointment,UUID.randomUUID(),procedure,
                Timestamp.from(completed.plusSeconds(30)),hash);
        owner.update("INSERT INTO billing.encounter_accounts(encounter_account_id,clinical_record_id,consultation_id,"
                +"patient_id,appointment_id,status,declared_procedure_ids) VALUES (?,?,?,?,?,'DECLARED',ARRAY[?]::uuid[])",
                account,record,consultation,patient,appointment,procedure);
        owner.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,"
                +"procedure_id,source_record_id,item_type,description,quantity,unit_price,created_by) "
                +"VALUES (?,?,?,?,?,?,'BASE_PROCEDURE','Resina',1,85000,?)",UUID.randomUUID(),account,patient,
                appointment,procedure,priced,actor);
    }

    @AfterEach void clean() {
        owner.update("DELETE FROM billing.billable_items WHERE encounter_account_id=?",account);
        owner.update("DELETE FROM billing.encounter_accounts WHERE encounter_account_id=?",account);
        owner.update("DELETE FROM billing.clinical_declarations WHERE clinical_record_id=?",record);
        owner.update("DELETE FROM billing.clinical_sources WHERE procedure_id=?",procedure);
        owner.update("DELETE FROM billing.clinical_procedures WHERE procedure_id=?",procedure);
    }

    private Optional<JdbcPendingPriceQuery.Account> mine() {
        var query=new JdbcPendingPriceQuery(runtime);
        for (int page=1; ; page++) {
            var result=query.list(page,100);
            var found=result.data().stream().filter(a -> a.encounterAccountId().equals(account)).findFirst();
            if (found.isPresent() || page>=result.meta().totalPages()) return found;
        }
    }

    @Test void listsOnlyTheDeclaredSourcesWithoutAPrice() {
        var item=mine().orElseThrow();
        assertEquals(patient,item.patientId());
        assertEquals(appointment,item.appointmentId());
        assertEquals(completed.plusSeconds(30),item.declaredAt());
        assertEquals(1,item.missingSources().size());
        var source=item.missingSources().getFirst();
        assertEquals(missing,source.sourceRecordId());
        assertEquals(procedure,source.procedureId());
        assertEquals("ADDITIONAL_MATERIAL",source.itemType());
        assertEquals("MAT-ADH",source.code());
        assertEquals("2.0000",source.quantity());
        assertEquals(completed,source.completedAt());
    }

    @Test void completedAccountsAreNotPending() {
        owner.update("UPDATE billing.encounter_accounts SET status='CHARGES_COMPLETED' WHERE encounter_account_id=?",account);
        assertTrue(mine().isEmpty());
    }
}
