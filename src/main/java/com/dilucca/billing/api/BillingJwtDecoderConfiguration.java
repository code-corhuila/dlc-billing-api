package com.dilucca.billing.api;

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

            if (jwt.getIssuedAt() == null
                    || jwt.getExpiresAt() == null
                    || !jwt.getAudience().contains(audience)) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error(
                                "invalid_token",
                                "Required JWT claims or audience missing",
                                null));
            }

            return OAuth2TokenValidatorResult.success();
        });

        return decoder;
    }
}
