package com.dilucca.billing.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dilucca.billing.api.pricing.ProcedurePriceQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "BILLING_IAM_JWKS_URI=http://127.0.0.1:9/api/v1/auth/jwks",
        "IAM_JWT_ISSUER=iam-test-issuer",
        "IAM_JWT_AUDIENCE=billing-test-audience"
})
class BillingJwtDecoderTest {

    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProcedurePriceQueryService queryService;

    @Test
    void providesJwtDecoderForBilling() {
        assertNotNull(jwtDecoder);
    }

    @Test
    void rejectsMalformedJwt() {
        assertThrows(
                JwtException.class,
                () -> jwtDecoder.decode("invalid-jwt"));
    }

    @Test
    void rejectsMissingRequiredIdentityClaims() {
        for (String missing : List.of(
                "sub", "jti", "sid", "roles", "permissions")) {
            Jwt jwt = validClaimsExcept(missing);

            assertTrue(
                    BillingJwtDecoderConfiguration
                            .requiredClaims()
                            .validate(jwt)
                            .hasErrors(),
                    "Expected rejection for missing claim: " + missing);
        }
    }

    private Jwt validClaimsExcept(String missing) {
        Instant now = Instant.now();

        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("staff-user")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("iss", "iam-test-issuer")
                .claim("aud", List.of("billing-test-audience"))
                .claim("jti", "test-token-id")
                .claim("sid", "test-session-id")
                .claim("roles", List.of("ADMINISTRATOR"))
                .claim("permissions", List.of("pricing:read"))
                .claims(claims -> claims.remove(missing))
                .build();
    }
}
