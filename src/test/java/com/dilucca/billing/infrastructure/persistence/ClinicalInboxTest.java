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
class ClinicalInboxTest {
    private JdbcTemplate owner,runtime;
    private final UUID event=UUID.randomUUID();
    @BeforeEach void connect() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),"billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
    }
    @AfterEach void clean() { owner.update("DELETE FROM billing.clinical_inbox WHERE event_id=?",event); }
    private JdbcClinicalInbox inbox() { return new JdbcClinicalInbox(runtime,JsonMapper.builder().build()); }
    private String body() throws Exception {
        try(var stream=getClass().getResourceAsStream("/clinical/procedure.json")) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("81000000-0000-4000-8000-000000000001",event.toString());
        }
    }
    @Test void commitsReceiptWithoutClinicalNarrativeAndRejectsAlteredDuplicate() throws Exception {
        String body=body();
        assertTrue(inbox().receive(body)); assertFalse(inbox().receive(body.replace("\n"," ")));
        String stored=owner.queryForObject("SELECT financial_fact::text FROM billing.clinical_inbox WHERE event_id=?",String.class,event);
        assertFalse(stored.contains("clinicalReason")); assertFalse(stored.contains("Protected synthetic"));
        assertTrue(stored.contains("0.5000"));
        assertInstanceOf(com.dilucca.billing.infrastructure.messaging.ClinicalFact.Procedure.class,inbox().fact(event).orElseThrow().payload());
        inbox().markProjected(event); inbox().markProjected(event);
        assertNotNull(owner.queryForObject("SELECT projected_at FROM billing.clinical_inbox WHERE event_id=?",java.sql.Timestamp.class,event));
        assertThrows(JdbcClinicalInbox.Conflict.class,() -> inbox().receive(body.replace("0.5000","0.5001")));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM billing.clinical_inbox WHERE event_id=?",Integer.class,event));
    }
    @Test void competingDeliveriesHaveExactlyOneDurableReceipt() throws Exception {
        String body=body();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> inbox().receive(body)); var b=pool.submit(() -> inbox().receive(body));
            assertEquals(Set.of(true,false),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));
        }
    }
    @Test void rejectsInvalidFactsBeforePersistence() throws Exception {
        String body=body();
        assertThrows(com.dilucca.billing.infrastructure.messaging.ClinicalEventDecoder.Invalid.class,
                () -> inbox().receive(body.replace("clinical-service","forged-owner")));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.clinical_inbox WHERE event_id=?",Integer.class,event));
    }
}
