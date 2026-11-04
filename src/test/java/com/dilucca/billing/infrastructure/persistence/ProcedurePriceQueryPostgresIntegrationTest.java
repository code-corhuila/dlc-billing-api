package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dilucca.billing.api.pricing.PricePage;
import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProcedurePriceQueryPostgresIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(
            named = "BILLING_TEST_JDBC_URL",
            matches = "jdbc:postgresql:.*")
    void readsRealPricesWithPaginationAndOrdering() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        System.getenv("BILLING_TEST_JDBC_URL"),
                        System.getenv("BILLING_TEST_DB_USER"),
                        System.getenv("BILLING_TEST_DB_PASSWORD"));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        JdbcProcedurePriceRepository writer =
                new JdbcProcedurePriceRepository(jdbc);

        JdbcProcedurePriceQueryRepository reader =
                new JdbcProcedurePriceQueryRepository(jdbc);

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();

        String prefix = "QUERY-" + UUID.randomUUID();
        Instant validFrom = Instant.parse("2026-01-01T00:00:00Z");

        long initialTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM billing.procedure_prices",
                Long.class);

        try {
            writer.save(
                    firstId,
                    new ProcedurePriceCatalog(
                            prefix + "-1",
                            "First integration procedure",
                            "150000.00",
                            validFrom),
                    1,
                    createdBy,
                    Instant.parse("1900-01-01T00:00:00Z"));

            writer.save(
                    secondId,
                    new ProcedurePriceCatalog(
                            prefix + "-2",
                            "Second integration procedure",
                            "250000.00",
                            validFrom),
                    1,
                    createdBy,
                    Instant.parse("1900-01-02T00:00:00Z"));

            PricePage result = reader.list(1, 2);

            assertEquals(initialTotal + 2, result.meta().total());
            assertEquals(1, result.meta().page());
            assertEquals(2, result.meta().limit());
            assertEquals((int) ((initialTotal + 3) / 2),
                    result.meta().totalPages());

            assertEquals(2, result.data().size());
            assertEquals(firstId, result.data().get(0).id());
            assertEquals(secondId, result.data().get(1).id());
            assertEquals("150000.00",
                    result.data().get(0).basePrice());
            assertEquals("250000.00",
                    result.data().get(1).basePrice());
            assertEquals("COP", result.data().get(0).currency());
            assertEquals(validFrom, result.data().get(0).validFrom());
            assertNull(result.data().get(0).validTo());
        } finally {
            jdbc.update(
                    "DELETE FROM billing.procedure_prices WHERE price_id IN (?, ?)",
                    firstId,
                    secondId);
        }
    }
}
