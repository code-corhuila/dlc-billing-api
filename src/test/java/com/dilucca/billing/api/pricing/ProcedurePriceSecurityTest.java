package com.dilucca.billing.api.pricing;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        mvc.perform(get("/procedure-prices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUntrustedIdentityHeaders() throws Exception {
        mvc.perform(get("/procedure-prices")
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

        mvc.perform(get("/procedure-prices")
                        .header("Authorization", "Bearer unauthorized-token"))
                .andExpect(status().isForbidden());
    }

    private void assertAllowedRole(String token, String role)
            throws Exception {

        when(jwtDecoder.decode(token))
                .thenReturn(staffJwt(token, role));

        mvc.perform(get("/procedure-prices")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
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
