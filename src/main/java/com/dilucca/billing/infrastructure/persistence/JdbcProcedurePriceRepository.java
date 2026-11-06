package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    private static final String LATEST_PRICE_SQL = """
            SELECT price_id, version, valid_from, valid_to, status
            FROM billing.procedure_prices
            WHERE procedure_code = ?
            ORDER BY version DESC
            LIMIT 1
            """;

    private static final String CLOSE_PRICE_SQL = """
            UPDATE billing.procedure_prices
            SET valid_to = ?, updated_at = ?
            WHERE price_id = ?
              AND valid_to IS NULL
              AND status = 'ACTIVE'
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

    public void saveNextVersion(
            UUID priceId,
            ProcedurePriceCatalog price,
            UUID createdBy,
            Instant now) {

        Objects.requireNonNull(priceId, "priceId is required");
        Objects.requireNonNull(price, "price is required");
        Objects.requireNonNull(createdBy, "createdBy is required");
        Objects.requireNonNull(now, "now is required");

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(
                        Objects.requireNonNull(
                                jdbcTemplate.getDataSource(),
                                "DataSource is required")));

        transaction.executeWithoutResult(status -> {
            lockProcedure(price.procedureCode());

            LatestPrice previous = findLatest(price.procedureCode());
            int nextVersion = 1;

            if (previous != null) {
                // Compare the same rounded timestamp that the PostgreSQL driver will persist.
                Timestamp storedStart = Objects.requireNonNull(jdbcTemplate.queryForObject(
                        "SELECT CAST(? AS TIMESTAMP WITH TIME ZONE)", Timestamp.class,
                        Timestamp.from(price.validity().validFrom())));
                if (!"ACTIVE".equals(previous.status())
                        || previous.validTo() != null
                        || !storedStart.toInstant()
                                .isAfter(previous.validFrom())) {
                    throw new IllegalArgumentException(
                            "Invalid price validity transition");
                }

                if (previous.version() == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "Price version limit exceeded");
                }

                int updated = jdbcTemplate.update(
                        CLOSE_PRICE_SQL,
                        storedStart,
                        Timestamp.from(now),
                        previous.priceId());

                if (updated != 1) {
                    throw new IllegalStateException(
                            "Previous price could not be closed");
                }

                nextVersion = previous.version() + 1;
            }

            save(priceId, price, nextVersion, createdBy, now);
        });
    }

    private void lockProcedure(String procedureCode) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(31703, hashtext(?))",
                (ResultSetExtractor<Void>) resultSet -> null,
                procedureCode);
    }

    private LatestPrice findLatest(String procedureCode) {
        return jdbcTemplate.query(
                LATEST_PRICE_SQL,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }

                    Timestamp validTo =
                            resultSet.getTimestamp("valid_to");

                    return new LatestPrice(
                            resultSet.getObject("price_id", UUID.class),
                            resultSet.getInt("version"),
                            resultSet.getTimestamp("valid_from")
                                    .toInstant(),
                            validTo == null
                                    ? null
                                    : validTo.toInstant(),
                            resultSet.getString("status"));
                },
                procedureCode);
    }

    private record LatestPrice(
            UUID priceId,
            int version,
            Instant validFrom,
            Instant validTo,
            String status) {
    }
}
