package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcProcedurePriceRepository {

    private static final String INSERT_SQL = """
            INSERT INTO billing.procedure_prices (
                price_id,
                procedure_code,
                name,
                base_price,
                currency,
                valid_from,
                valid_to,
                status,
                version,
                created_by,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcedurePriceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate, "jdbcTemplate is required");
    }

    public void save(
            UUID priceId,
            ProcedurePriceCatalog price,
            int version,
            UUID createdBy,
            Instant now) {

        Objects.requireNonNull(priceId, "priceId is required");
        Objects.requireNonNull(price, "price is required");
        Objects.requireNonNull(createdBy, "createdBy is required");
        Objects.requireNonNull(now, "now is required");

        if (version < 1) {
            throw new IllegalArgumentException(
                    "version must be positive");
        }

        Timestamp validTo = price.validity().validTo() == null
                ? null
                : Timestamp.from(price.validity().validTo());

        jdbcTemplate.update(
                INSERT_SQL,
                priceId,
                price.procedureCode(),
                price.name(),
                price.basePrice(),
                "COP",
                Timestamp.from(price.validity().validFrom()),
                validTo,
                "ACTIVE",
                version,
                createdBy,
                Timestamp.from(now),
                Timestamp.from(now));
    }
}
