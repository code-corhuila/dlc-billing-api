package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class ClinicalSourceProjectionTest {
    private JdbcTemplate owner,runtime;
    private final Map<String,String> ids=new HashMap<>();
    @BeforeEach void connect() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),"billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
    }
    private String id(String prefix,int value) { return ids.computeIfAbsent(prefix+":"+value,key -> UUID.randomUUID().toString()); }
    private String fixture(String name) throws Exception {
        String raw;
        try(var source=getClass().getResourceAsStream("/clinical/"+name+".json")) { raw=new String(source.readAllBytes(),StandardCharsets.UTF_8); }
        for(String prefix:List.of("81","82","83")) for(int n=1;n<=9;n++)
            raw=raw.replace(prefix+"000000-0000-4000-8000-"+String.format("%012d",n),id(prefix,n));
        return raw;
    }
    private JdbcClinicalSourceProjection service() { return new JdbcClinicalSourceProjection(runtime,JsonMapper.builder().build()); }
    @AfterEach void clean() {
        owner.update("DELETE FROM billing.clinical_sources WHERE procedure_id=?::uuid",id("81",3));
        owner.update("DELETE FROM billing.clinical_procedures WHERE procedure_id=?::uuid",id("81",3));
        owner.update("DELETE FROM billing.clinical_consultations WHERE consultation_id=?::uuid",id("82",3));
        owner.update("DELETE FROM billing.clinical_declarations WHERE consultation_id=?::uuid",id("82",3));
        for(String prefix:List.of("81","82","83")) owner.update("DELETE FROM billing.clinical_inbox WHERE event_id=?::uuid",id(prefix,1));
    }
    @Test void acceptsReorderedFactsWithoutInventingPricesOrDuplicateSources() throws Exception {
        String declaration=fixture("declaration"),procedure=fixture("procedure"),consultation=fixture("consultation");
        service().ingest(declaration); service().ingest(procedure); service().ingest(consultation);
        service().ingest(procedure);
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM billing.clinical_sources WHERE procedure_id=?::uuid",Integer.class,id("81",3)));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM billing.clinical_declarations WHERE consultation_id=?::uuid",Integer.class,id("82",3)));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.billable_items WHERE procedure_id=?::uuid",Integer.class,id("81",3)));
        assertEquals(3,owner.queryForObject("SELECT count(*) FROM billing.clinical_inbox WHERE event_id IN (?::uuid,?::uuid,?::uuid) AND projected_at IS NOT NULL",Integer.class,id("81",1),id("82",1),id("83",1)));
    }
    @Test void businessSourceConflictRollsBackTheNewInboxReceipt() throws Exception {
        String procedure=fixture("procedure"); service().ingest(procedure);
        String changed=procedure.replace(id("81",1),id("83",1)).replace("RESTORATION","DIFFERENT");
        assertThrows(JdbcClinicalSourceProjection.Conflict.class,() -> service().ingest(changed));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.clinical_inbox WHERE event_id=?::uuid",Integer.class,id("83",1)));
    }
    @Test void mismatchedDeclarationCannotCorrelateAnotherPatient() throws Exception {
        service().ingest(fixture("procedure"));
        String declaration=fixture("declaration").replace(id("81",4),UUID.randomUUID().toString());
        assertThrows(JdbcClinicalSourceProjection.Conflict.class,() -> service().ingest(declaration));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.clinical_declarations WHERE consultation_id=?::uuid",Integer.class,id("82",3)));
    }
}
