package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.AdditionalPriceRuleQueryService;
import com.dilucca.billing.api.pricing.PaginatedMeta;
import com.dilucca.billing.api.pricing.PriceRule;
import com.dilucca.billing.api.pricing.PriceRulePage;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdditionalPriceRuleQueryRepository implements AdditionalPriceRuleQueryService {
    private final JdbcTemplate jdbc;

    public JdbcAdditionalPriceRuleQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public PriceRulePage list(int page, int limit) {
        if (page < 1 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid pagination parameters");
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, limit);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Pagination offset exceeds supported range", overflow);
        }
        long total = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM billing.additional_price_rules", Long.class));
        long pages = total / limit + (total % limit == 0 ? 0 : 1);
        if (pages > Integer.MAX_VALUE) {
            throw new IllegalStateException("Total pages exceed supported range");
        }
        var rules = jdbc.query("""
                SELECT rule_id, rule_code, item_type, unit_price, requires_approval,
                       valid_from, created_at, updated_at, version
                FROM billing.additional_price_rules
                ORDER BY created_at ASC, rule_id ASC LIMIT ? OFFSET ?
                """, (row, index) -> new PriceRule(row.getObject("rule_id", UUID.class),
                row.getString("rule_code"), row.getString("item_type"),
                row.getBigDecimal("unit_price").toPlainString(), row.getBoolean("requires_approval"),
                row.getTimestamp("valid_from").toInstant(), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), row.getInt("version")), limit, offset);
        return new PriceRulePage(rules, new PaginatedMeta(page, limit, total, (int) pages));
    }
}
