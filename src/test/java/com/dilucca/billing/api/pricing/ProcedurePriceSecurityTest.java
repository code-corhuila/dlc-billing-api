package com.dilucca.billing.api.pricing;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class ProcedurePriceSecurityTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.dilucca.billing.infrastructure.security.IamSessionValidationClient iamSession;
    @org.junit.jupiter.api.BeforeEach
    void eligibleIamSession() {
        org.mockito.Mockito.when(iamSession.isActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }


    private static final String PRICES_PATH =
            "/api/v1/procedure-prices";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProcedurePriceQueryService queryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejectsUnauthenticatedProcedurePriceRequests() throws Exception {
        mvc.perform(get(PRICES_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUntrustedIdentityHeaders() throws Exception {
        mvc.perform(get(PRICES_PATH)
                        .header("X-User-Id",
                                "00000000-0000-0000-0000-000000000001")
                        .header("X-User-Role", "ADMINISTRATOR"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsAdministratorToReadProcedurePrices() throws Exception {
        assertAllowedRole("administrator-token", "ADMINISTRATOR");
    }

    @Test
    void allowsDentistToReadProcedurePrices() throws Exception {
        assertAllowedRole("dentist-token", "DENTIST");
    }

    @Test
    void allowsSecretaryAssistantToReadProcedurePrices() throws Exception {
        assertAllowedRole("secretary-token", "SECRETARY_ASSISTANT");
    }

    @Test
    void rejectsAuthenticatedUserWithoutAllowedRole() throws Exception {
        when(jwtDecoder.decode("unauthorized-token"))
                .thenReturn(staffJwt("unauthorized-token", "UNAUTHORIZED"));

        mvc.perform(get(PRICES_PATH)
                        .header("Authorization", "Bearer unauthorized-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnauthenticatedPriceCreation() throws Exception {
        mvc.perform(post(PRICES_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsAdministratorToReachPriceCreationValidation()
            throws Exception {
        when(jwtDecoder.decode("administrator-write-token"))
                .thenReturn(staffJwt(
                        "administrator-write-token", "ADMINISTRATOR"));

        mvc.perform(post(PRICES_PATH)
                        .header("Authorization",
                                "Bearer administrator-write-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDentistPriceCreation() throws Exception {
        assertRejectedWriteRole("dentist-write-token", "DENTIST");
    }

    @Test
    void rejectsSecretaryAssistantPriceCreation() throws Exception {
        assertRejectedWriteRole(
                "secretary-write-token", "SECRETARY_ASSISTANT");
    }

    private void assertAllowedRole(String token, String role)
            throws Exception {
        when(jwtDecoder.decode(token))
                .thenReturn(staffJwt(token, role));

        mvc.perform(get(PRICES_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void assertRejectedWriteRole(String token, String role)
            throws Exception {
        when(jwtDecoder.decode(token))
                .thenReturn(staffJwt(token, role));

        mvc.perform(post(PRICES_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private Jwt staffJwt(String token, String role) {
        Instant now = Instant.now();

        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0000-000000000001")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("roles", List.of(role))
                .build();
    }
}
