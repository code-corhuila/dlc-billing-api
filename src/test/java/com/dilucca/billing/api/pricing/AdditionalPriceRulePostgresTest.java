package com.dilucca.billing.api.pricing;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
@Import(ProcedurePriceCreatePostgresTest.Database.class)
@EnabledIfEnvironmentVariable(named = "BILLING_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class AdditionalPriceRulePostgresTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.dilucca.billing.infrastructure.security.IamSessionValidationClient iamSession;
    @org.junit.jupiter.api.BeforeEach
    void eligibleIamSession() {
        org.mockito.Mockito.when(iamSession.isActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @Test
    void readsExactRulesWithStableOrderingAndPaginationOverHttp() throws Exception {
        when(decoder.decode("reader")).thenReturn(Jwt.withTokenValue("reader").header("alg", "RS256")
                .subject(UUID.randomUUID().toString()).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600)).claim("roles", List.of("SECRETARY_ASSISTANT")).build());
        UUID actor = UUID.randomUUID();
        long prefix = UUID.randomUUID().getMostSignificantBits();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM billing.additional_price_rules", Long.class);
        try {
            for (int index = 1; index <= 3; index++) {
                jdbc.update("""
                        INSERT INTO billing.additional_price_rules
                        (rule_id, rule_code, item_type, unit_price, requires_approval,
                         valid_from, version, created_by, created_at, updated_at)
                        VALUES (?, ?, 'ADDITIONAL_MATERIAL', 150000.01, ?, ?, 1, ?, ?, ?)
                        """, new UUID(prefix, index), actor + "-" + index, index == 3,
                        Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), actor,
                        Timestamp.from(Instant.parse("1900-01-01T00:00:00Z")),
                        Timestamp.from(Instant.parse("1900-01-01T00:00:00Z")));
            }
            mvc.perform(get("/api/v1/additional-price-rules").header("Authorization", "Bearer reader")
                    .param("page", "1").param("limit", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(total + 3))
                    .andExpect(jsonPath("$.meta.totalPages").value((total + 4) / 2))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value(new UUID(prefix, 1).toString()))
                    .andExpect(jsonPath("$.data[1].id").value(new UUID(prefix, 2).toString()))
                    .andExpect(jsonPath("$.data[0].unitPrice").value("150000.01"))
                    .andExpect(jsonPath("$.data[0].requiresApproval").value(false))
                    .andExpect(jsonPath("$.data[0].itemType").value("ADDITIONAL_MATERIAL"))
                    .andExpect(jsonPath("$.data[0].validFrom").value("2026-01-01T00:00:00Z"))
                    .andExpect(jsonPath("$.data[0].version").value(1))
                    .andExpect(jsonPath("$.data[0].createdBy").doesNotExist())
                    .andExpect(jsonPath("$.data[0].currency").doesNotExist())
                    .andExpect(jsonPath("$.data[0].validTo").doesNotExist());
            mvc.perform(get("/api/v1/additional-price-rules").header("Authorization", "Bearer reader")
                    .param("page", "2").param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(new UUID(prefix, 3).toString()))
                    .andExpect(jsonPath("$.data[0].requiresApproval").value(true));
            mvc.perform(get("/api/v1/additional-price-rules").header("Authorization", "Bearer reader")
                    .param("page", Long.toString(total + 5)).param("limit", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        } finally {
            jdbc.update("DELETE FROM billing.additional_price_rules WHERE created_by = ?", actor);
        }
    }
}
