package com.dilucca.billing.api.pricing;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
class AdditionalPriceRuleHttpTest {
    private static final String PATH = "/api/v1/additional-price-rules";
    private static final String BODY = """
            {"code":"MATERIAL", "itemType":"ADDITIONAL_MATERIAL", "unitPrice":"1000.00",
             "requiresApproval":false, "validFrom":"2026-01-01T00:00:00Z"}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void identities() {
        for (String role : List.of("ADMINISTRATOR", "SECRETARY_ASSISTANT", "DENTIST")) {
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg", "RS256")
                    .subject("00000000-0000-0000-0000-000000000001")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                    .claim("roles", List.of(role)).build());
        }
    }

    @Test
    void allowsAdministrativeRolesToReadAnEmptyPage() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        for (String role : List.of("ADMINISTRATOR", "SECRETARY_ASSISTANT")) {
            mvc.perform(get(PATH).header("Authorization", "Bearer " + role))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.meta.page").value(1))
                    .andExpect(jsonPath("$.meta.limit").value(20))
                    .andExpect(jsonPath("$.meta.total").value(0))
                    .andExpect(jsonPath("$.meta.totalPages").value(0));
        }
    }

    @Test
    void rejectsDentistAndUnauthenticatedRequestsBeforeQuerying() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", "Bearer DENTIST"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidPaginationBeforeQuerying() throws Exception {
        for (String[] parameters : List.of(new String[]{"0", "20"}, new String[]{"1", "0"},
                new String[]{"1", "101"}, new String[]{"2147483647", "100"}, new String[]{"abc", "20"})) {
            mvc.perform(get(PATH).header("Authorization", "Bearer ADMINISTRATOR")
                    .param("page", parameters[0]).param("limit", parameters[1]))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidRuleCreationBeforeQuerying() throws Exception {
        for (String body : List.of("", "null", "[]", "{}", "{",
                BODY.replace("false", "true"), BODY.replace("false", "\"false\""),
                BODY.replace("ADDITIONAL_MATERIAL", "UNSUPPORTED"),
                BODY.replace("\"code\":\"MATERIAL\"", "\"code\":\" \""),
                BODY.replace("\"code\":\"MATERIAL\"", "\"code\":\"" + "X".repeat(81) + "\""),
                BODY.replace("\"requiresApproval\":false,", ""),
                BODY.replace("1000.00", "0.00"), BODY.replace("1000.00", "1.001"),
                BODY.replace("\"1000.00\"", "1000.00"), BODY.replace("1000.00", "1000000000000.00"),
                BODY.replace("2026-01-01T00:00:00Z", "2026-01-01"),
                BODY.replace("}", ",\"expression\":\"amount * 2\"}"))) {
            mvc.perform(post(PATH).header("Authorization", "Bearer ADMINISTRATOR")
                    .header("Idempotency-Key", "4108e44f-7db0-471b-95e6-cc1d8f1742b9")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        for (String key : List.of("invalid", "1-1-1-1-1")) {
            mvc.perform(post(PATH).header("Authorization", "Bearer ADMINISTRATOR")
                    .header("Idempotency-Key", key).contentType("application/json").content(BODY))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH).header("Authorization", "Bearer ADMINISTRATOR")
                .contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }

    @Test
    void restrictsRuleCreationToAdministrator() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        for (String role : List.of("DENTIST", "SECRETARY_ASSISTANT")) {
            mvc.perform(post(PATH).header("Authorization", "Bearer " + role)
                    .contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(jdbc);
    }
}
