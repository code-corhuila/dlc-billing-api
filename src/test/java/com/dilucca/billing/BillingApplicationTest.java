package com.dilucca.billing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "BILLING_IAM_JWKS_URI=http://127.0.0.1:9/api/v1/auth/jwks",
        "IAM_JWT_ISSUER=iam-test-issuer",
        "IAM_JWT_AUDIENCE=billing-test-audience"
})
class BillingApplicationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.dilucca.billing.infrastructure.security.IamSessionValidationClient iamSession;
    @org.junit.jupiter.api.BeforeEach
    void eligibleIamSession() {
        org.mockito.Mockito.when(iamSession.isActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }


    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @Test
    void applicationContextLoads() {
    }
}
