package com.dilucca.billing.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class AdditionalPriceRuleSchemaTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            System.getenv("BILLING_TEST_JDBC_URL"), System.getenv("BILLING_TEST_DB_USER"),
            System.getenv("BILLING_TEST_DB_PASSWORD")));
    private final UUID actor = UUID.randomUUID();
    private final String code = "RULE-" + UUID.randomUUID();
    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void enforcesRuleMoneyTypesVersionsAndNonoverlappingValidity() {
        assertNotNull(jdbc.queryForObject(
                "SELECT to_regclass('billing.additional_price_rules')::text", String.class));
        try {
            insert(code, "ADDITIONAL_MATERIAL", "150000.00", 1, start);
            assertEquals("150000.00", jdbc.queryForObject("SELECT unit_price::text "
                    + "FROM billing.additional_price_rules WHERE rule_code = ?", String.class, code));
            assertEquals("COP", jdbc.queryForObject("SELECT currency "
                    + "FROM billing.additional_price_rules WHERE rule_code = ?", String.class, code));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(code + "-zero", "ADDITIONAL_MATERIAL", "0.00", 1, start));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(code + "-type", "UNSUPPORTED", "1.00", 1, start));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(code + "-version", "ADDITIONAL_MATERIAL", "1.00", 0, start));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(code, "ADDITIONAL_MATERIAL", "175000.00", 2, start));
            Instant next = start.plusSeconds(86400);
            jdbc.update("UPDATE billing.additional_price_rules SET valid_to = ? WHERE rule_code = ?",
                    Timestamp.from(next), code);
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(code, "ADDITIONAL_MATERIAL", "175000.00", 1, next));
            insert(code, "ADDITIONAL_MATERIAL", "175000.00", 2, next);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM billing.additional_price_rules "
                    + "WHERE rule_code = ?", Integer.class, code));
        } finally {
            jdbc.update("DELETE FROM billing.additional_price_rules WHERE created_by = ?", actor);
        }
    }

    private void insert(String source, String type, String price, int version, Instant from) {
        jdbc.update("""
                INSERT INTO billing.additional_price_rules
                (rule_id, rule_code, item_type, unit_price, valid_from, version, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::numeric, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), source, type, price, Timestamp.from(from), version,
                actor, Timestamp.from(start), Timestamp.from(start));
    }
}
