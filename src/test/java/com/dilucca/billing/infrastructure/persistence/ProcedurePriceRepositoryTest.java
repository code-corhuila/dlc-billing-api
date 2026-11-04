package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ProcedurePriceRepositoryTest {

    @Test
    void savesProcedurePriceToBillingSchema() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcProcedurePriceRepository repository =
                new JdbcProcedurePriceRepository(jdbcTemplate);

        ProcedurePriceCatalog price = new ProcedurePriceCatalog(
                "PROC-001",
                "Dental cleaning",
                "150000.00",
                Instant.parse("2026-01-01T00:00:00Z"));

        UUID priceId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        repository.save(priceId, price, 1, createdBy, now);

        ArgumentCaptor<String> sql =
                ArgumentCaptor.forClass(String.class);

        verify(jdbcTemplate).update(
                sql.capture(),
                any(Object[].class));

        assertTrue(
                sql.getValue().contains(
                        "INSERT INTO billing.procedure_prices"));
    }
}
