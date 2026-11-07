package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.domain.messaging.EventPublisher;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RUNTIME_DB_PASSWORD",matches=".+")
class OutboxRelayTest {
    private final UUID event=UUID.randomUUID(),aggregate=UUID.randomUUID();
    private JdbcTemplate owner,runtime;
    @BeforeEach void seed() {
        owner=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                System.getenv("BILLING_TEST_DB_USER"),System.getenv("BILLING_TEST_DB_PASSWORD")));
        runtime=new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                "billing_runtime",System.getenv("BILLING_TEST_RUNTIME_DB_PASSWORD")));
        owner.update("""
                INSERT INTO billing.outbox_events(event_id,aggregate_id,event_type,event_version,occurred_at,event_body)
                VALUES (?,?,'InvoiceIssued',1,now(),jsonb_build_object('eventId',?::text,'aggregateId',?::text,
                'eventType','InvoiceIssued','version',1,'producer','billing-service','payload','{}'::jsonb,'metadata','{}'::jsonb))
                """,event,aggregate,event,aggregate);
    }
    @AfterEach void clean() { owner.update("DELETE FROM billing.outbox_events WHERE event_id=?",event); }
    private String status() { return owner.queryForObject("SELECT status FROM billing.outbox_deliveries WHERE event_id=?",String.class,event); }
    private int attempts() { return owner.queryForObject("SELECT attempts FROM billing.outbox_deliveries WHERE event_id=?",Integer.class,event); }

    @Test void publishesCommittedCanonicalIdentityOnceAndKeepsEventImmutable() {
        String original=owner.queryForObject("SELECT event_body::text FROM billing.outbox_events WHERE event_id=?",String.class,event);
        var calls=new AtomicInteger();
        var relay=new JdbcOutboxRelay(runtime,(type,id,body) -> {
            assertEquals("InvoiceIssued",type); assertEquals(event,id); assertEquals(original,body); calls.incrementAndGet();
        });
        assertEquals(1,relay.relay(Instant.now(),10));
        assertEquals("PUBLISHED",status()); assertEquals(1,attempts());
        assertEquals(0,relay.relay(Instant.now().plusSeconds(100),10));
        assertEquals(1,calls.get());
    }
    @Test void failureBacksOffAndRetriesWithUnchangedEvent() {
        var calls=new ArrayList<UUID>();
        var relay=new JdbcOutboxRelay(runtime,(type,id,body) -> {
            calls.add(id); if (calls.size()==1) throw new EventPublisher.Unavailable();
        });
        Instant now=Instant.now();
        assertEquals(0,relay.relay(now,10)); assertEquals("PENDING",status()); assertEquals(1,attempts());
        var due=owner.queryForObject("SELECT next_attempt_at FROM billing.outbox_deliveries WHERE event_id=?",java.sql.Timestamp.class,event).toInstant();
        assertTrue(!due.isBefore(now.plusSeconds(5)) && due.isBefore(now.plusSeconds(6)));
        assertEquals(0,relay.relay(now,10));
        assertEquals(1,relay.relay(now.plusSeconds(6),10));
        assertEquals(List.of(event,event),calls); assertEquals("PUBLISHED",status()); assertEquals(2,attempts());
    }
    @Test void exhaustedWorkIsVisibleAndDoesNotRetryForever() {
        var relay=new JdbcOutboxRelay(runtime,(type,id,body) -> { throw new EventPublisher.Unavailable(); });
        Instant now=Instant.now();
        for (int attempt=0;attempt<10;attempt++) assertEquals(0,relay.relay(now.plusSeconds(3600L*attempt),10));
        assertEquals("FAILED",status()); assertEquals(10,attempts());
        assertEquals(0,relay.relay(now.plusSeconds(100000),10));
        assertThrows(IllegalArgumentException.class,() -> relay.relay(now,0));
    }
    @Test void missingMountedCredentialKeepsTheEventPending(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        var configuration=new com.dilucca.billing.api.BillingOutboxConfiguration(runtime,
                directory.resolve("not-provisioned").toString(),"billing.events",true,10);
        configuration.publish();
        assertEquals("PENDING",status()); assertEquals(1,attempts());
    }
    @Test void competingWorkersSkipLockedDelivery() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new AtomicInteger();
        var relay=new JdbcOutboxRelay(runtime,(type,id,body) -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Test publisher not released"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new EventPublisher.Unavailable(); }
        });
        try (var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> relay.relay(Instant.now(),1));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try { assertEquals(0,pool.submit(() -> relay.relay(Instant.now(),1)).get(2,TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(1,first.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,calls.get());
    }
    @Test void databaseFailureAfterPublishReplaysSameIdentityRatherThanLosingEvent() {
        String fault="relay_fault_"+event.toString().replace("-","");
        var sent=new ArrayList<String>();
        var relay=new JdbcOutboxRelay(runtime,(type,id,body) -> sent.add(id+body));
        owner.execute("CREATE FUNCTION billing."+fault+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_id='"
                +event+"'::uuid THEN RAISE EXCEPTION 'test-only persistence failure'; END IF; RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER "+fault+" BEFORE UPDATE ON billing.outbox_deliveries FOR EACH ROW EXECUTE FUNCTION billing."+fault+"()");
        try { assertThrows(org.springframework.dao.DataAccessException.class,() -> relay.relay(Instant.now(),10)); }
        finally {
            owner.execute("DROP TRIGGER "+fault+" ON billing.outbox_deliveries"); owner.execute("DROP FUNCTION billing."+fault+"()");
        }
        assertEquals("PENDING",status()); assertEquals(0,attempts());
        assertEquals(1,relay.relay(Instant.now(),10));
        assertEquals(2,sent.size()); assertEquals(sent.getFirst(),sent.getLast());
    }
}
