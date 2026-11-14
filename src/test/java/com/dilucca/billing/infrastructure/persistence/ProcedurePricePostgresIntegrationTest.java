package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ProcedurePricePostgresIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(
            named = "BILLING_TEST_JDBC_URL",
            matches = "jdbc:postgresql:.*")
    void persistsProcedurePriceInPostgres() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        System.getenv("BILLING_TEST_JDBC_URL"),
                        System.getenv("BILLING_TEST_DB_USER"),
                        System.getenv("BILLING_TEST_DB_PASSWORD"));

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcProcedurePriceRepository repository =
                new JdbcProcedurePriceRepository(jdbcTemplate);

        UUID priceId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        String procedureCode =
                "TEST-" + UUID.randomUUID().toString();

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
}
