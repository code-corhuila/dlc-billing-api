package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProcedurePricePostgresIntegrationTest {

    private JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        System.getenv("BILLING_TEST_JDBC_URL"),
                        System.getenv("BILLING_TEST_DB_USER"),
                        System.getenv("BILLING_TEST_DB_PASSWORD"));

        return new JdbcTemplate(dataSource);
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "BILLING_TEST_JDBC_URL",
            matches = "jdbc:postgresql:.*")
    void persistsProcedurePriceInPostgres() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        JdbcProcedurePriceRepository repository =
                new JdbcProcedurePriceRepository(jdbcTemplate);

        UUID priceId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        String procedureCode =
                "TEST-" + UUID.randomUUID();

        Instant validFrom =
                Instant.parse("2026-01-01T00:00:00Z");

        ProcedurePriceCatalog price =
                new ProcedurePriceCatalog(
                        procedureCode,
                        "Integration test procedure",
                        "150000.00",
                        validFrom);

        try {
            repository.save(
                    priceId,
                    price,
                    1,
                    createdBy,
                    Instant.now());

            BigDecimal storedPrice = jdbcTemplate.queryForObject(
                    """
                    SELECT base_price
                    FROM billing.procedure_prices
                    WHERE price_id = ?
                    """,
                    BigDecimal.class,
                    priceId);

            assertEquals(
                    new BigDecimal("150000.00"),
                    storedPrice);

            String storedCode = jdbcTemplate.queryForObject(
                    """
                    SELECT procedure_code
                    FROM billing.procedure_prices
                    WHERE price_id = ?
                    """,
                    String.class,
                    priceId);

            assertEquals(procedureCode, storedCode);
        } finally {
            jdbcTemplate.update(
                    """
                    DELETE FROM billing.procedure_prices
                    WHERE price_id = ?
                    """,
                    priceId);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "BILLING_TEST_JDBC_URL",
            matches = "jdbc:postgresql:.*")
    void closesPreviousValidityWhenCreatingNextVersion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        JdbcProcedurePriceRepository repository =
                new JdbcProcedurePriceRepository(jdbcTemplate);

        UUID originalId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String procedureCode = "TEST-" + UUID.randomUUID();

        Instant firstStart =
                Instant.parse("2026-01-01T00:00:00Z");
        Instant nextStart =
                Instant.parse("2026-02-01T00:00:00Z");

        ProcedurePriceCatalog original =
                new ProcedurePriceCatalog(
                        procedureCode,
                        "Dental cleaning",
                        "150000.00",
                        firstStart);

        ProcedurePriceCatalog replacement =
                new ProcedurePriceCatalog(
                        procedureCode,
                        "Dental cleaning",
                        "175000.00",
                        nextStart);

        try {
            repository.save(
                    originalId,
                    original,
                    1,
                    actorId,
                    Instant.now());

            repository.saveNextVersion(
                    nextId,
                    replacement,
                    actorId,
                    Instant.now());

            Timestamp originalEnd = jdbcTemplate.queryForObject(
                    """
                    SELECT valid_to
                    FROM billing.procedure_prices
                    WHERE price_id = ?
                    """,
                    Timestamp.class,
                    originalId);

            assertEquals(Timestamp.from(nextStart), originalEnd);

            Integer nextVersion = jdbcTemplate.queryForObject(
                    """
                    SELECT version
                    FROM billing.procedure_prices
                    WHERE price_id = ?
                    """,
                    Integer.class,
                    nextId);

            assertEquals(2, nextVersion);

            Timestamp replacementEnd = jdbcTemplate.queryForObject(
                    """
                    SELECT valid_to
                    FROM billing.procedure_prices
                    WHERE price_id = ?
                    """,
                    Timestamp.class,
                    nextId);

            assertNull(replacementEnd);

            BigDecimal originalAmount = jdbcTemplate.queryForObject(
                    """
                    SELECT base_price
                    FROM billing.procedure_prices
                    WHERE price_id = ?
                    """,
                    BigDecimal.class,
                    originalId);

            assertEquals(
                    new BigDecimal("150000.00"),
                    originalAmount);
        } finally {
            jdbcTemplate.update(
                    """
                    DELETE FROM billing.procedure_prices
                    WHERE procedure_code = ?
                    """,
                    procedureCode);
        }
    }
}