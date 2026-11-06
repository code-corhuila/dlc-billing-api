package com.dilucca.billing.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dilucca.billing.api.pricing.*;
import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@AutoConfigureMockMvc
class IamSessionSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean ProcedurePriceQueryService query;
    @MockitoBean ProcedurePriceCreateService creation;
    @MockitoBean IamSessionValidationClient iam;
    private static final String BODY = """
            {"procedureCode":"IAM-CHECK","name":"IAM check","basePrice":"10.01",
             "validFrom":"2026-11-06T00:00:00Z"}
            """;

    @BeforeEach
    void identity() {
        when(decoder.decode("staff-token")).thenReturn(Jwt.withTokenValue("staff-token")
                .header("alg","RS256").subject("11111111-1111-4111-8111-111111111111")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .claim("roles", List.of("ADMINISTRATOR")).build());
        when(iam.isActive("staff-token")).thenReturn(true);
    }

    @Test
    void revalidatesAnIdenticalPostBeforeIdempotentReplay() throws Exception {
        when(creation.create(any(), any(), any())).thenReturn(new CreatedResource(201,
                "/api/v1/procedure-prices/22222222-2222-4222-8222-222222222222", "{}"));
        var request = post("/api/v1/procedure-prices").header("Authorization","Bearer staff-token")
                .header("Idempotency-Key","33333333-3333-4333-8333-333333333333")
                .contentType("application/json").content(BODY);
        mvc.perform(request).andExpect(status().isCreated());
        when(iam.isActive("staff-token")).thenReturn(false);
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("Bearer")));
        verify(creation, times(1)).create(any(), any(), any());
        verify(iam, times(2)).isActive("staff-token");
    }

    @Test
    void deniesAllCatalogReadsAndWritesForAnInactiveSession() throws Exception {
        when(iam.isActive("staff-token")).thenReturn(false);
        for (String path : List.of("/api/v1/procedure-prices", "/api/v1/additional-price-rules")) {
            mvc.perform(get(path).header("Authorization","Bearer staff-token")).andExpect(status().isUnauthorized());
            mvc.perform(post(path).header("Authorization","Bearer staff-token")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(jdbc, query, creation);
    }

    @Test
    void returnsSafe503AndNeverCallsBusinessCodeWhenIamIsUnavailable() throws Exception {
        when(iam.isActive("staff-token")).thenThrow(new IllegalStateException("sensitive-provider-detail"));
        mvc.perform(get("/api/v1/procedure-prices").header("Authorization","Bearer staff-token"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
        verifyNoInteractions(query, jdbc, creation);
    }

    @Test
    void checksSignatureBeforeSendingCredentialsToIam() throws Exception {
        when(decoder.decode("forged-token")).thenThrow(new BadJwtException("Rejected"));
        mvc.perform(get("/api/v1/procedure-prices").header("Authorization","Bearer forged-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(iam, query, jdbc, creation);
    }
}
