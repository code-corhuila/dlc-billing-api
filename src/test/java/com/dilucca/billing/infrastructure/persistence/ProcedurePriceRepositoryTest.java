package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ProcedurePriceRepositoryTest {

    private static final Instant VALID_FROM =
            Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void savesProcedurePriceWithCorrectSqlParameters() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcProcedurePriceRepository repository =
                new JdbcProcedurePriceRepository(jdbcTemplate);

        ProcedurePriceCatalog price = new ProcedurePriceCatalog(
                "PROC-001",
                "Dental cleaning",
                "150000.00",
                VALID_FROM);

        UUID priceId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-02T10:00:00Z");

        repository.save(priceId, price, 1, createdBy, now);

        ArgumentCaptor<String> sql =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters =
                ArgumentCaptor.forClass(Object[].class);

        verify(jdbcTemplate).update(
                sql.capture(),
                parameters.capture());

        String statement = sql.getValue();
        Object[] values = parameters.getValue();

        assertTrue(statement.contains(
                "INSERT INTO billing.procedure_prices"));
        assertEquals(12, statement.chars()
                .filter(character -> character == '?').count());
        assertEquals(12, values.length);

        assertAll(
                () -> assertEquals(priceId, values[0]),
                () -> assertEquals("PROC-001", values[1]),
                () -> assertEquals("Dental cleaning", values[2]),
                () -> assertEquals(
                        new BigDecimal("150000.00"), values[3]),
                () -> assertEquals("COP", values[4]),
                () -> assertEquals(
                        Timestamp.from(VALID_FROM), values[5]),
                () -> assertNull(values[6]),
                () -> assertEquals("ACTIVE", values[7]),
                () -> assertEquals(1, values[8]),
                () -> assertEquals(createdBy, values[9]),
                () -> assertEquals(
                        Timestamp.from(now), values[10]),
                () -> assertEquals(
                        Timestamp.from(now), values[11]));
    }

    @Test
    void rejectsInvalidPriceVersionBeforeWriting() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcProcedurePriceRepository repository =
                new JdbcProcedurePriceRepository(jdbcTemplate);

        ProcedurePriceCatalog price = new ProcedurePriceCatalog(
                "PROC-001",
                "Dental cleaning",
                "150000.00",
                VALID_FROM);

        assertThrows(IllegalArgumentException.class,
                () -> repository.save(
                        UUID.randomUUID(),
                        price,
                        0,
                        UUID.randomUUID(),
                        VALID_FROM));

        verifyNoInteractions(jdbcTemplate);
    }
}
