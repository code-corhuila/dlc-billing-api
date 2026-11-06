package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class ProcedurePriceConcurrencyTest {
    @Test
    void serializesConcurrentReplacementsWithoutLosingHistory() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                System.getenv("BILLING_TEST_DB_USER"), System.getenv("BILLING_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var repository = new JdbcProcedurePriceRepository(jdbc);
        String code = "RACE-" + UUID.randomUUID();
        UUID original = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant nextStart = Instant.parse("2026-02-01T00:00:00Z");
        var executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = source.getConnection()) {
            repository.save(original, new ProcedurePriceCatalog(code, "Original", "150000.00",
                    Instant.parse("2026-01-01T00:00:00Z")), 1, actor, Instant.now());
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement(
                    "SELECT pg_advisory_xact_lock(31703, hashtext(?))")) {
                lock.setString(1, code);
                lock.execute();
            }
            java.util.concurrent.Callable<Boolean> write = () -> {
                try {
                    repository.saveNextVersion(UUID.randomUUID(),
                            new ProcedurePriceCatalog(code, "Replacement", "175000.00", nextStart),
                            actor, Instant.now());
                    return true;
                } catch (IllegalArgumentException conflict) {
                    return false;
                }
            };
            var first = executor.submit(write);
            var second = executor.submit(write);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting;
                do {
                    waiting = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory'
                            AND classid = 31703 AND objid = (hashtext(?)::bigint & 4294967295)
                            AND NOT granted AND database = (SELECT oid FROM pg_database
                            WHERE datname = current_database())
                            """, Integer.class, code);
                    if (waiting == 2) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                assertEquals(2, waiting, "Both writers must contend for the procedure lock");
            } finally {
                blocker.rollback();
            }
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), jdbc.queryForList(
                    "SELECT version FROM billing.procedure_prices WHERE procedure_code = ? ORDER BY version",
                    Integer.class, code));
            assertEquals(java.sql.Timestamp.from(nextStart), jdbc.queryForObject(
                    "SELECT valid_to FROM billing.procedure_prices WHERE price_id = ?",
                    java.sql.Timestamp.class, original));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM billing.procedure_prices "
                    + "WHERE procedure_code = ? AND valid_to IS NULL", Integer.class, code));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
            jdbc.update("DELETE FROM billing.procedure_prices WHERE procedure_code = ?", code);
        }
    }
}
