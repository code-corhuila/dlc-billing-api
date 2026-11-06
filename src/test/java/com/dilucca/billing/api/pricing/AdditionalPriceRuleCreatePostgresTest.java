package com.dilucca.billing.api.pricing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
@Import(ProcedurePriceCreatePostgresTest.Database.class)
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class AdditionalPriceRuleCreatePostgresTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.json.JsonMapper mapper;
    @MockitoBean JwtDecoder decoder;
    private final UUID actor = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final String code = "CREATE-RULE-" + UUID.randomUUID();

    @BeforeEach
    void identity() {
        when(decoder.decode("admin")).thenReturn(Jwt.withTokenValue("admin").header("alg", "RS256")
                .subject(actor.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("roles", List.of("ADMINISTRATOR")).build());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM billing.idempotency_records WHERE actor_id = ?", actor.toString());
        jdbc.update("DELETE FROM billing.additional_price_rules WHERE created_by = ?", actor);
        jdbc.update("DELETE FROM billing.procedure_prices WHERE created_by = ?", actor);
    }

    @Test
    void createsNextVersionAndReplaysOriginalRuleSnapshot() throws Exception {
        var original = send(key, body("150000.01", "2026-01-01T00:00:00Z"), 201);
        String location = original.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.matches("/api/v1/additional-price-rules/[0-9a-f-]{36}"));
        assertEquals(actor, jdbc.queryForObject("SELECT created_by FROM billing.additional_price_rules "
                + "WHERE rule_code = ?", UUID.class, code));
        send(UUID.randomUUID(), body("175000.02", "2026-02-01T00:00:00Z"), 201);
        var replay = send(key, body("150000.01", "2026-01-01T00:00:00Z"), 201);
        assertEquals(original.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(location, replay.getResponse().getHeader("Location"));
        assertEquals(List.of(1, 2), jdbc.queryForList("SELECT version FROM billing.additional_price_rules "
                + "WHERE rule_code = ? ORDER BY version", Integer.class, code));
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), jdbc.queryForObject(
                "SELECT valid_to FROM billing.additional_price_rules WHERE rule_code = ? AND version = 1",
                java.sql.Timestamp.class, code).toInstant());
        assertEquals("150000.01", jdbc.queryForObject("SELECT unit_price::text "
                + "FROM billing.additional_price_rules WHERE rule_code = ? AND version = 1", String.class, code));
    }

    @Test
    void rejectsChangedPayloadButScopesKeysByOperation() throws Exception {
        send(key, body("150000.01", "2026-01-01T00:00:00Z"), 201);
        assertTrue(send(key, body("175000.02", "2026-01-01T00:00:00Z"), 409)
                .getResponse().getContentAsString().contains("IDEMPOTENCY_CONFLICT"));
        String price = "{\"procedureCode\":\"" + code + "\",\"name\":\"Cleaning\","
                + "\"basePrice\":\"150000.01\",\"validFrom\":\"2026-01-01T00:00:00Z\"}";
        mvc.perform(post("/api/v1/procedure-prices").header("Authorization", "Bearer admin")
                .header("Idempotency-Key", key).contentType("application/json").content(price))
                .andExpect(status().isCreated());
        assertEquals(2, receipts());
    }

    @Test
    void rejectsRoundedValidityConflictsWithoutConsumingTheKey() throws Exception {
        send(key, body("150000.01", "2026-01-01T00:00:00.000000100Z"), 201);
        UUID replacement = UUID.randomUUID();
        send(replacement, body("175000.02", "2026-01-01T00:00:00.000000200Z"), 409);
        assertEquals(1, receipts());
        send(replacement, body("175000.02", "2026-01-01T00:00:00.000000600Z"), 201);
        assertEquals(2, receipts());
    }

    @Test
    void serializesIdenticalRetries() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<MvcResult> request = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return send(key, body("150000.01", "2026-01-01T00:00:00Z"), 201);
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS).getResponse().getContentAsString(),
                    second.get(20, TimeUnit.SECONDS).getResponse().getContentAsString());
        }
        assertEquals(1, receipts());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM billing.additional_price_rules "
                + "WHERE rule_code = ?", Integer.class, code));
    }

    private int receipts() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM billing.idempotency_records WHERE actor_id = ?",
                Integer.class, actor.toString());
    }

    @Test
    void receiptFailureRollsBackTheRuleAndPreviousValidity() throws Exception {
        send(key, body("150000.01", "2026-01-01T00:00:00Z"), 201);
        var failingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO billing.idempotency_records")) {
                    return super.update("INSERT INTO billing.idempotency_records (record_id) VALUES (?)",
                            UUID.randomUUID());
                }
                return super.update(sql, args);
            }
        };
        var service = new com.dilucca.billing.infrastructure.persistence.JdbcAdditionalPriceRuleCreationService(
                failingJdbc, mapper);
        UUID failedKey = UUID.randomUUID();
        var request = new com.dilucca.billing.domain.pricing.AdditionalPriceRuleCatalog(
                code, "ADDITIONAL_MATERIAL", "175000.02", Instant.parse("2026-02-01T00:00:00Z"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.create(failedKey, actor, request));
        assertEquals(1, receipts());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM billing.additional_price_rules "
                + "WHERE rule_code = ?", Integer.class, code));
        assertNull(jdbc.queryForObject("SELECT valid_to FROM billing.additional_price_rules "
                + "WHERE rule_code = ?", java.sql.Timestamp.class, code));
        send(failedKey, body("175000.02", "2026-02-01T00:00:00Z"), 201);
        assertEquals(2, receipts());
    }

    @Test
    void acceptsEveryContractTypeAndScopesTheKeyByRule() throws Exception {
        int index = 0;
        for (String type : List.of("ADDITIONAL_MATERIAL", "ADDITIONAL_PROCEDURE",
                "COMPLEXITY_ADJUSTMENT", "OTHER_AUTHORIZED_CHARGE")) {
            String request = body("150000.01", "2026-01-01T00:00:00Z")
                    .replace(code, code + "-" + index++).replace("ADDITIONAL_MATERIAL", type);
            mvc.perform(post("/api/v1/additional-price-rules").header("Authorization", "Bearer admin")
                    .header("Idempotency-Key", key).contentType("application/json").content(request))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.itemType").value(type))
                    .andExpect(jsonPath("$.requiresApproval").value(false));
        }
        assertEquals(4, receipts());
    }

    @Test
    void serializesDistinctKeysContendingForOneRule() throws Exception {
        send(key, body("150000.01", "2026-01-01T00:00:00Z"), 201);
        try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT pg_advisory_xact_lock(31705, hashtext(?))")) {
                lock.setString(1, code);
                lock.execute();
            }
            java.util.concurrent.Callable<MvcResult> write = () -> mvc.perform(
                    post("/api/v1/additional-price-rules").header("Authorization", "Bearer admin")
                            .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                            .content(body("175000.02", "2026-02-01T00:00:00Z"))).andReturn();
            var first = executor.submit(write);
            var second = executor.submit(write);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int waiting;
                do {
                    waiting = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = 31705
                            AND objid = (hashtext(?)::bigint & 4294967295) AND NOT granted
                            AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                            """, Integer.class, code);
                    if (waiting == 2) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                assertEquals(2, waiting, "Both requests must contend for the rule lock");
            } finally {
                blocker.rollback();
            }
            assertEquals(List.of(201, 409), List.of(first.get(20, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(20, TimeUnit.SECONDS).getResponse().getStatus()).stream().sorted().toList());
        }
        assertEquals(2, receipts());
        assertEquals(List.of(1, 2), jdbc.queryForList("SELECT version FROM billing.additional_price_rules "
                + "WHERE rule_code = ? ORDER BY version", Integer.class, code));
    }

    private MvcResult send(UUID requestKey, String body, int status) throws Exception {
        var result = mvc.perform(post("/api/v1/additional-price-rules").header("Authorization", "Bearer admin")
                .header("Idempotency-Key", requestKey).contentType("application/json").content(body))
                .andExpect(status().is(status));
        if (status == 201) {
            result.andExpect(jsonPath("$.id").isString()).andExpect(jsonPath("$.unitPrice").isString())
                    .andExpect(jsonPath("$.itemType").value("ADDITIONAL_MATERIAL"))
                    .andExpect(jsonPath("$.requiresApproval").value(false))
                    .andExpect(jsonPath("$.validFrom").isString()).andExpect(jsonPath("$.version").isNumber())
                    .andExpect(jsonPath("$.createdBy").doesNotExist()).andExpect(jsonPath("$.validTo").doesNotExist());
        }
        return result.andReturn();
    }

    private String body(String price, String from) {
        return "{\"code\":\"" + code + "\",\"itemType\":\"ADDITIONAL_MATERIAL\",\"unitPrice\":\""
                + price + "\",\"requiresApproval\":false,\"validFrom\":\"" + from + "\"}";
    }
}
