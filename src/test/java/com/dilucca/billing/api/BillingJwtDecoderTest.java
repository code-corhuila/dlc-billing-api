package com.dilucca.billing.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dilucca.billing.api.pricing.ProcedurePriceQueryService;

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
}
