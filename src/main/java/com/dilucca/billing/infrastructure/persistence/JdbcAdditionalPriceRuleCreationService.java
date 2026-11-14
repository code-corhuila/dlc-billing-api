package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.AdditionalPriceRuleCreateService;
import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.api.pricing.PriceCreationConflict;
import com.dilucca.billing.domain.pricing.AdditionalPriceRuleCatalog;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JdbcAdditionalPriceRuleCreationService implements AdditionalPriceRuleCreateService {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public JdbcAdditionalPriceRuleCreationService(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public CreatedResource create(UUID key, UUID actor, AdditionalPriceRuleCatalog request) {
        return new JdbcIdempotentCreation(jdbc, mapper).execute(key, actor, "BillingCreateadditionalpricerule",
                request.code(), List.of(request.code(), request.itemType(), request.unitPrice(), false,
                        request.validFrom().toString()), now -> {
                    jdbc.query("SELECT pg_advisory_xact_lock(31705, hashtext(?))",
                            (ResultSetExtractor<Void>) rows -> null, request.code());
                    Latest previous = latest(request.code());
                    int version = 1;
                    if (previous != null) {
                        Timestamp from = jdbc.queryForObject("SELECT CAST(? AS TIMESTAMP WITH TIME ZONE)",
                                Timestamp.class, Timestamp.from(request.validFrom()));
                        if (!"ACTIVE".equals(previous.status()) || previous.to() != null
                                || !from.toInstant().isAfter(previous.from()) || previous.version() == Integer.MAX_VALUE) {
                            throw new PriceCreationConflict("CONFLICT");
                        }
                        int closed = jdbc.update("""
                                UPDATE billing.additional_price_rules SET valid_to = ?, updated_at = ?
                                WHERE rule_id = ? AND valid_to IS NULL AND status = 'ACTIVE'
                                """, from, Timestamp.from(now), previous.id());
                        if (closed != 1) throw new PriceCreationConflict("CONFLICT");
                        version = previous.version() + 1;
                    }
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO billing.additional_price_rules
                            (rule_id, rule_code, item_type, unit_price, requires_approval,
                             valid_from, version, created_by, created_at, updated_at)
                            VALUES (?, ?, ?, ?, FALSE, ?, ?, ?, ?, ?)
                            """, id, request.code(), request.itemType(), new BigDecimal(request.unitPrice()),
                            Timestamp.from(request.validFrom()), version, actor, Timestamp.from(now), Timestamp.from(now));
                    var rule = jdbc.queryForObject("SELECT * FROM billing.additional_price_rules WHERE rule_id = ?",
                            JdbcAdditionalPriceRuleQueryRepository.RULE_MAPPER, id);
                    return new CreatedResource(201, "/api/v1/additional-price-rules/" + id, mapper.writeValueAsString(rule));
                });
    }

    private Latest latest(String code) {
        return jdbc.query("""
                SELECT rule_id, version, valid_from, valid_to, status FROM billing.additional_price_rules
                WHERE rule_code = ? ORDER BY version DESC LIMIT 1
                """, rows -> {
            if (!rows.next()) return null;
            Timestamp to = rows.getTimestamp("valid_to");
            return new Latest(rows.getObject("rule_id", UUID.class), rows.getInt("version"),
                    rows.getTimestamp("valid_from").toInstant(), to == null ? null : to.toInstant(), rows.getString("status"));
        }, code);
    }

    private record Latest(UUID id, int version, Instant from, Instant to, String status) { }
}
