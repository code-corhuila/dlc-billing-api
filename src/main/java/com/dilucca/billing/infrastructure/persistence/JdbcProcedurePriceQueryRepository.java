package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.PaginatedMeta;
import com.dilucca.billing.api.pricing.Price;
import com.dilucca.billing.api.pricing.PricePage;
import com.dilucca.billing.api.pricing.ProcedurePriceQueryService;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcedurePriceQueryRepository
        implements ProcedurePriceQueryService {

    private static final String COUNT_SQL = """
            SELECT COUNT(*)
            FROM billing.procedure_prices
            """;

    private static final String LIST_SQL = """
            SELECT price_id, procedure_code, name, base_price,
                   currency, valid_from, valid_to, status,
                   created_at, updated_at, version
            FROM billing.procedure_prices
            ORDER BY created_at ASC, price_id ASC
            LIMIT ? OFFSET ?
            """;

    private static final RowMapper<Price> PRICE_MAPPER =
            (rs, rowNum) -> {
                Timestamp validFrom = rs.getTimestamp("valid_from");
                Timestamp validTo = rs.getTimestamp("valid_to");
                Timestamp createdAt = rs.getTimestamp("created_at");
                Timestamp updatedAt = rs.getTimestamp("updated_at");

                return new Price(
                        rs.getObject("price_id", java.util.UUID.class),
                        rs.getString("procedure_code"),
                        rs.getString("name"),
                        rs.getBigDecimal("base_price").toPlainString(),
                        rs.getString("currency"),
                        validFrom == null ? null : validFrom.toInstant(),
                        validTo == null ? null : validTo.toInstant(),
                        rs.getString("status"),
                        createdAt == null ? null : createdAt.toInstant(),
                        updatedAt == null ? null : updatedAt.toInstant(),
                        rs.getInt("version"));
            };

    private final JdbcTemplate jdbc;

    public JdbcProcedurePriceQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(
                jdbc, "JdbcTemplate is required");
    }

    @Override
    public PricePage list(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Invalid pagination parameters");
        }

        int offset;
        try {
            offset = Math.multiplyExact(page - 1, limit);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Pagination offset exceeds supported range",
                    exception);
        }

        Long count = jdbc.queryForObject(COUNT_SQL, Long.class);
        long total = Objects.requireNonNull(
                count, "Total count is required");

        long pages = total / limit
                + (total % limit == 0 ? 0 : 1);

        if (pages > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Total pages exceed supported range");
        }

        List<Price> prices = jdbc.query(
                LIST_SQL,
                PRICE_MAPPER,
                limit,
                offset);

        return new PricePage(
                prices,
                new PaginatedMeta(
                        page,
                        limit,
                        total,
                        (int) pages));
    }
}
