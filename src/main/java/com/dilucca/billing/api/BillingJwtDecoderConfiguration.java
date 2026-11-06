package com.dilucca.billing.api;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class BillingJwtDecoderConfiguration {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${BILLING_IAM_JWKS_URI}") String jwksUri,
            @Value("${IAM_JWT_ISSUER}") String issuer,
            @Value("${IAM_JWT_AUDIENCE}") String audience) {

        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> defaults =
                JwtValidators.createDefaultWithIssuer(issuer);

        decoder.setJwtValidator(jwt -> {
            var result = defaults.validate(jwt);
            if (result.hasErrors()) {
                return result;
            }

            result = requiredClaims().validate(jwt);
            if (result.hasErrors()) {
                return result;
            }

            if (jwt.getIssuedAt() == null
                    || jwt.getExpiresAt() == null
                    || !jwt.getAudience().contains(audience)) {
                return invalidToken("Required JWT claims or audience missing");
            }

            return OAuth2TokenValidatorResult.success();
        });

        return decoder;
    }

    static OAuth2TokenValidator<Jwt> requiredClaims() {
        return jwt -> {
            if (!hasText(jwt.getClaim("sub"))
                    || !hasText(jwt.getClaim("jti"))
                    || !hasText(jwt.getClaim("sid"))
                    || !hasRoles(jwt.getClaim("roles"))
                    || !hasStrings(jwt.getClaim("permissions"))) {
                return invalidToken("Required identity claims missing or invalid");
            }

            return OAuth2TokenValidatorResult.success();
        };
    }

    private static boolean hasText(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static boolean hasStrings(Object value) {
        return value instanceof List<?> values
                && values.stream().allMatch(BillingJwtDecoderConfiguration::hasText);
    }

    private static boolean hasRoles(Object value) {
        return hasStrings(value) && !((List<?>) value).isEmpty();
    }

    private static OAuth2TokenValidatorResult invalidToken(String message) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", message, null));
    }
}
