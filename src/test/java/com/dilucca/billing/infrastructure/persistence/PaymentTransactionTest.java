package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class PaymentTransactionTest {
    private final UUID invoice=UUID.randomUUID(),actor=UUID.randomUUID();
    private final String method="TEST-"+UUID.randomUUID().toString().substring(0,20);
    private JdbcTemplate owner,runtime;
    private final JsonMapper mapper=JsonMapper.builder().build();
    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                "billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
        owner.update("INSERT INTO billing.payment_methods(method) VALUES (?)",method);
        owner.update("INSERT INTO billing.invoices(invoice_id,patient_id,total,balance,created_by) VALUES (?,?,10.01,10.01,?)",
                invoice,UUID.randomUUID(),actor);
        owner.update("UPDATE billing.invoices SET status='ISSUED',version=2,invoice_number=?,issued_at=now() WHERE invoice_id=?",
                "BIL-"+invoice,invoice);
    }
    @AfterEach void clean() {
        owner.update("DELETE FROM billing.payment_receipts WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.payments WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.outbox_events WHERE aggregate_id=?",invoice);
        owner.update("DELETE FROM billing.idempotency_records WHERE actor_id=?",actor.toString());
        owner.update("DELETE FROM billing.invoices WHERE invoice_id=?",invoice);
        owner.update("DELETE FROM billing.payment_methods WHERE method=?",method);
    }
    private JdbcPaymentRecording service() { return new JdbcPaymentRecording(runtime,mapper); }
    private com.dilucca.billing.api.pricing.CreatedResource pay(UUID key,String amount,long version) {
        return service().record(key,actor,invoice,new BigDecimal(amount),method,version,UUID.randomUUID());
    }
    @Test void derivesBalanceHistoryEventsAndReplaysEvenAfterMethodDisabled() {
        UUID key=UUID.randomUUID();
        var first=pay(key,"4.01",2);
        assertEquals(201,first.status());
        var body=mapper.readTree(first.body());
        assertEquals("4.01",body.get("amount").stringValue());
        assertEquals(actor.toString(),body.get("recordedBy").stringValue());
        owner.update("UPDATE billing.payment_methods SET active=false WHERE method=?",method);
        assertEquals(first,pay(key,"4.01",2));
        assertThrows(IllegalArgumentException.class,() -> pay(UUID.randomUUID(),"1.00",3));
        owner.update("UPDATE billing.payment_methods SET active=true WHERE method=?",method);
        assertThrows(JdbcPaymentRecording.Conflict.class,() -> pay(key,"4.02",2));
        assertThrows(JdbcPaymentRecording.Conflict.class,() -> pay(UUID.randomUUID(),"6.01",3));
        assertThrows(JdbcPaymentRecording.Conflict.class,() -> pay(UUID.randomUUID(),"1.00",2));
        pay(UUID.randomUUID(),"6.00",3);
        assertEquals("PAID:0.00:4",owner.queryForObject("SELECT status||':'||balance||':'||version FROM billing.invoices WHERE invoice_id=?",String.class,invoice));
        assertEquals(2,owner.queryForObject("SELECT count(*) FROM billing.payments WHERE invoice_id=?",Integer.class,invoice));
        assertEquals(3,owner.queryForObject("SELECT count(*) FROM billing.outbox_events WHERE aggregate_id=?",Integer.class,invoice));
        var snapshot=mapper.readTree(owner.queryForObject("SELECT invoice_snapshot::text FROM billing.payment_receipts WHERE invoice_id=? AND invoice_version=3",String.class,invoice));
        assertEquals("6.00",snapshot.get("balance").stringValue());
        assertEquals("PARTIALLY_PAID",snapshot.get("status").stringValue());
        assertEquals(first,pay(key,"4.01",2));
    }
    @Test void locksCompetingPaymentsAndSameKeyReplaysConcurrently() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            UUID key=UUID.randomUUID();
            var a=pool.submit(() -> pay(key,"4.01",2)); var b=pool.submit(() -> pay(key,"4.01",2));
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            Callable<Integer> attempt=() -> { try { return pay(UUID.randomUUID(),"6.00",3).status(); }
                catch(JdbcPaymentRecording.Conflict stale) { return 409; } };
            var c=pool.submit(attempt); var d=pool.submit(attempt);
            assertEquals(Set.of(201,409),Set.of(c.get(10,TimeUnit.SECONDS),d.get(10,TimeUnit.SECONDS)));
        }
    }
    @Test void lateOutboxFailureRollsBackPaymentBalanceAndReplay() {
        String name="fail_payment_"+invoice.toString().replace("-","");
        owner.execute("CREATE FUNCTION billing."+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.aggregate_id='"+invoice+"'::uuid THEN RAISE EXCEPTION 'Synthetic failure'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON billing.outbox_events FOR EACH ROW EXECUTE FUNCTION billing."+name+"()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,() -> pay(UUID.randomUUID(),"1.00",2));
            assertEquals("10.01",owner.queryForObject("SELECT balance::text FROM billing.invoices WHERE invoice_id=?",String.class,invoice));
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.payments WHERE invoice_id=?",Integer.class,invoice));
            assertEquals(0,owner.queryForObject("SELECT count(*) FROM billing.idempotency_records WHERE actor_id=?",Integer.class,actor.toString()));
        } finally { owner.execute("DROP TRIGGER "+name+" ON billing.outbox_events"); owner.execute("DROP FUNCTION billing."+name+"()"); }
    }
}
