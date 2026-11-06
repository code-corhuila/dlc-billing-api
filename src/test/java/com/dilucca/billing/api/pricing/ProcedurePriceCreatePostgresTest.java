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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class ProcedurePriceCreatePostgresTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.dilucca.billing.infrastructure.security.IamSessionValidationClient iamSession;
    @org.junit.jupiter.api.BeforeEach
    void eligibleIamSession() {
        org.mockito.Mockito.when(iamSession.isActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @TestConfiguration
    static class Database {
        @Bean
        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(new DriverManagerDataSource(System.getenv("BILLING_TEST_JDBC_URL"),
                    System.getenv("BILLING_TEST_DB_USER"), System.getenv("BILLING_TEST_DB_PASSWORD")));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.json.JsonMapper mapper;
    @MockitoBean JwtDecoder decoder;
    private final UUID actor = UUID.randomUUID();
    private final UUID otherActor = UUID.randomUUID();
    private final String code = "HTTP-" + UUID.randomUUID();
    private final UUID key = UUID.randomUUID();

    @BeforeEach
    void identities() {
        when(decoder.decode("admin")).thenReturn(jwt(actor, "ADMINISTRATOR"));
        when(decoder.decode("other-admin")).thenReturn(jwt(otherActor, "ADMINISTRATOR"));
        when(decoder.decode("dentist")).thenReturn(jwt(actor, "DENTIST"));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM billing.idempotency_records WHERE resource_key IN (?, ?)", code, code + "-other");
        jdbc.update("DELETE FROM billing.procedure_prices WHERE procedure_code IN (?, ?)", code, code + "-other");
    }

    @Test
    void createsAndReplaysOriginalResponseEvenAfterAnotherVersion() throws Exception {
        MvcResult first = send(key, body("150000.00", "2026-01-01T00:00:00Z"), "admin", 201);
        String location = first.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.matches("/api/v1/procedure-prices/[0-9a-f-]{36}"));
        assertEquals(actor, jdbc.queryForObject("SELECT created_by FROM billing.procedure_prices "
                + "WHERE procedure_code = ?", UUID.class, code));
        send(UUID.randomUUID(), body("175000.00", "2026-02-01T00:00:00Z"), "admin", 201);
        MvcResult replay = send(key, body("150000.00", "2026-01-01T00:00:00Z"), "admin", 201);
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(location, replay.getResponse().getHeader("Location"));
        String reordered = "{\"validFrom\":\"2026-01-01T00:00:00+00:00\",\"basePrice\":\"150000.00\","
                + "\"name\":\"Cleaning\",\"procedureCode\":\"" + code + "\"}";
        assertEquals(first.getResponse().getContentAsString(),
                send(key, reordered, "admin", 201).getResponse().getContentAsString());
        assertEquals(List.of(1, 2), jdbc.queryForList("SELECT version FROM billing.procedure_prices "
                + "WHERE procedure_code = ? ORDER BY version", Integer.class, code));
        assertEquals(2, receiptCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM billing.idempotency_records "
                + "WHERE resource_key = ? AND expires_at < created_at + INTERVAL '7 days'", Integer.class, code));
    }

    @Test
    void conflictsOnChangedPayloadAndReauthorizesRetries() throws Exception {
        send(key, body("150000.00", "2026-01-01T00:00:00Z"), "admin", 201);
        assertTrue(send(key, body("175000.00", "2026-01-01T00:00:00Z"), "admin", 409)
                .getResponse().getContentAsString().contains("IDEMPOTENCY_CONFLICT"));
        send(key, body("150000.00", "2026-01-01T00:00:00Z"), "dentist", 403);
        mvc.perform(post("/api/v1/procedure-prices").header("Idempotency-Key", key)
                .contentType("application/json").content(body("150000.00", "2026-01-01T00:00:00Z")))
                .andExpect(status().isUnauthorized());
        assertEquals(1, receiptCount());
    }

    @Test
    void scopesKeysByActorAndProcedureAndRollsBackBusinessConflicts() throws Exception {
        send(key, body("150000.00", "2026-01-01T00:00:00Z"), "admin", 201);
        send(key, body("175000.00", "2026-02-01T00:00:00Z"), "other-admin", 201);
        send(key, body("150000.00", "2026-01-01T00:00:00Z").replace(code, code + "-other"), "admin", 201);
        UUID failedKey = UUID.randomUUID();
        send(failedKey, body("175000.00", "2026-02-01T00:00:00Z"), "admin", 409);
        assertEquals(2, receiptCount());
        send(failedKey, body("175000.00", "2026-03-01T00:00:00Z"), "admin", 201);
        assertEquals(3, receiptCount());
    }

    @Test
    void concurrentRetriesCreateOnePriceAndOneReceipt() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<MvcResult> request = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return send(key, body("150000.00", "2026-01-01T00:00:00Z"), "admin", 201);
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            assertEquals(first.get(15, TimeUnit.SECONDS).getResponse().getContentAsString(),
                    second.get(15, TimeUnit.SECONDS).getResponse().getContentAsString());
        }
        assertEquals(1, receiptCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM billing.procedure_prices "
                + "WHERE procedure_code = ?", Integer.class, code));
    }

    private int receiptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM billing.idempotency_records WHERE resource_key = ?",
                Integer.class, code);
    }

    @Test
    void comparesValidityAtDatabasePrecisionWithoutConsumingConflictingKeys() throws Exception {
        String initial = body("150000.00", "2026-01-01T00:00:00.000000100Z");
        MvcResult first = send(key, initial, "admin", 201);
        UUID replacementKey = UUID.randomUUID();
        send(replacementKey, body("175000.00", "2026-01-01T00:00:00.000000200Z"), "admin", 409);
        assertEquals(1, receiptCount());
        assertNull(jdbc.queryForObject("SELECT valid_to FROM billing.procedure_prices "
                + "WHERE procedure_code = ?", java.sql.Timestamp.class, code));
        send(replacementKey, body("175000.00", "2026-01-01T00:00:00.000000600Z"), "admin", 201);
        assertEquals(first.getResponse().getContentAsString(),
                send(key, initial, "admin", 201).getResponse().getContentAsString());
        assertEquals(2, receiptCount());
        assertEquals(List.of(1, 2), jdbc.queryForList("SELECT version FROM billing.procedure_prices "
                + "WHERE procedure_code = ? ORDER BY version", Integer.class, code));
    }

    @Test
    void receiptInsertFailureRollsBackPriceAndPreviousValidity() throws Exception {
        send(key, body("150000.00", "2026-01-01T00:00:00Z"), "admin", 201);
        var failingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO billing.idempotency_records")) {
                    // Cause a real PostgreSQL NOT NULL violation after the business write.
                    return super.update("INSERT INTO billing.idempotency_records (record_id) VALUES (?)",
                            UUID.randomUUID());
                }
                return super.update(sql, args);
            }
        };
        var service = new com.dilucca.billing.infrastructure.persistence.JdbcProcedurePriceCreationService(
                failingJdbc, mapper);
        UUID failedKey = UUID.randomUUID();
        var request = new com.dilucca.billing.domain.pricing.ProcedurePriceCatalog(
                code, "Cleaning", "175000.00", Instant.parse("2026-02-01T00:00:00Z"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.create(failedKey, actor, request));
        assertEquals(1, receiptCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM billing.procedure_prices "
                + "WHERE procedure_code = ?", Integer.class, code));
        assertNull(jdbc.queryForObject("SELECT valid_to FROM billing.procedure_prices "
                + "WHERE procedure_code = ?", java.sql.Timestamp.class, code));
        send(failedKey, body("175000.00", "2026-02-01T00:00:00Z"), "admin", 201);
        assertEquals(2, receiptCount());
    }

    private MvcResult send(UUID requestKey, String body, String token, int expected) throws Exception {
        var result = mvc.perform(post("/api/v1/procedure-prices").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", requestKey).contentType("application/json").content(body))
                .andExpect(status().is(expected));
        if (expected == 201) {
            result.andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.id").isString())
                    .andExpect(jsonPath("$.basePrice").value(mapper.readTree(body).get("basePrice").asString()))
                    .andExpect(jsonPath("$.currency").value("COP"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.version").isNumber())
                    .andExpect(jsonPath("$.validFrom").isString())
                    .andExpect(jsonPath("$.createdAt").isString())
                    .andExpect(jsonPath("$.updatedAt").isString());
        }
        return result.andReturn();
    }

    private String body(String amount, String from) {
        return "{\"procedureCode\":\"" + code + "\",\"name\":\"Cleaning\",\"basePrice\":\""
                + amount + "\",\"validFrom\":\"" + from + "\"}";
    }

    private Jwt jwt(UUID subject, String role) {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject(subject.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("roles", List.of(role)).build();
    }
}
