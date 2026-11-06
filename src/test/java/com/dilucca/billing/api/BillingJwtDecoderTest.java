package com.dilucca.billing.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dilucca.billing.api.pricing.ProcedurePriceQueryService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.dilucca.billing.infrastructure.security.IamSessionValidationClient iamSession;
    @org.junit.jupiter.api.BeforeEach
    void eligibleIamSession() {
        org.mockito.Mockito.when(iamSession.isActive(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }


    private static final String ISSUER = "iam-test-issuer";
    private static final String AUDIENCE = "billing-test-audience";
    private static final String KEY_ID = "billing-test-key";

    private static KeyPair authorizedKey;
    private static KeyPair unauthorizedKey;
    private static HttpServer jwksServer;
    private static JwtDecoder signedJwtDecoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProcedurePriceQueryService queryService;

    @BeforeAll
    static void startJwksServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        authorizedKey = generator.generateKeyPair();
        unauthorizedKey = generator.generateKeyPair();

        RSAKey publicJwk = new RSAKey.Builder(
                (RSAPublicKey) authorizedKey.getPublic())
                .keyID(KEY_ID)
                .algorithm(JWSAlgorithm.RS256)
                .build();

        byte[] jwks = new JWKSet(publicJwk)
                .toString()
                .getBytes(StandardCharsets.UTF_8);

        jwksServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);

        jwksServer.createContext("/api/v1/auth/jwks", exchange -> {
            exchange.getResponseHeaders()
                    .set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var output = exchange.getResponseBody()) {
                output.write(jwks);
            }
        });

        jwksServer.start();

        String jwksUri = "http://127.0.0.1:"
                + jwksServer.getAddress().getPort()
                + "/api/v1/auth/jwks";

        signedJwtDecoder = new BillingJwtDecoderConfiguration()
                .jwtDecoder(jwksUri, ISSUER, AUDIENCE);
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

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

    @Test
    void acceptsValidRs256Signature() throws Exception {
        String token = signedToken(
                authorizedKey, ISSUER, AUDIENCE, 3600);

        Jwt decoded = signedJwtDecoder.decode(token);

        assertNotNull(decoded);
        assertTrue(decoded.getAudience().contains(AUDIENCE));
    }

    @Test
    void rejectsSignatureFromUnauthorizedKey() throws Exception {
        String token = signedToken(
                unauthorizedKey, ISSUER, AUDIENCE, 3600);

        assertThrows(
                JwtException.class,
                () -> signedJwtDecoder.decode(token));
    }

    @Test
    void rejectsIncorrectIssuer() throws Exception {
        String token = signedToken(
                authorizedKey, "another-issuer", AUDIENCE, 3600);

        assertThrows(
                JwtException.class,
                () -> signedJwtDecoder.decode(token));
    }

    @Test
    void rejectsIncorrectAudience() throws Exception {
        String token = signedToken(
                authorizedKey, ISSUER, "another-audience", 3600);

        assertThrows(
                JwtException.class,
                () -> signedJwtDecoder.decode(token));
    }

    @Test
    void rejectsExpiredSignedToken() throws Exception {
        String token = signedToken(
                authorizedKey, ISSUER, AUDIENCE, -3600);

        assertThrows(
                JwtException.class,
                () -> signedJwtDecoder.decode(token));
    }

    private static String signedToken(
            KeyPair key,
            String issuer,
            String audience,
            long expirationOffsetSeconds) throws Exception {

        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("staff-user")
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(7200)))
                .expirationTime(Date.from(
                        now.plusSeconds(expirationOffsetSeconds)))
                .jwtID("test-token-id")
                .claim("sid", "test-session-id")
                .claim("roles", List.of("ADMINISTRATOR"))
                .claim("permissions", List.of("pricing:read"))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(KEY_ID)
                        .build(),
                claims);

        jwt.sign(new RSASSASigner(
                (RSAPrivateKey) key.getPrivate()));

        return jwt.serialize();
    }

    private Jwt validClaimsExcept(String missing) {
        Instant now = Instant.now();

        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("staff-user")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("iss", ISSUER)
                .claim("aud", List.of(AUDIENCE))
                .claim("jti", "test-token-id")
                .claim("sid", "test-session-id")
                .claim("roles", List.of("ADMINISTRATOR"))
                .claim("permissions", List.of("pricing:read"))
                .claims(claims -> claims.remove(missing))
                .build();
    }
}