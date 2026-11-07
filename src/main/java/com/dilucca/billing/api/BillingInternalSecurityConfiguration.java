package com.dilucca.billing.api;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/** Separate trust domain: service identities never use staff sessions or roles. */
@Configuration
public class BillingInternalSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain billingInternalChain(HttpSecurity http,Environment env) throws Exception {
        JwtDecoder decoder=token -> {
            try {
                Path file=Path.of(env.getRequiredProperty("BILLING_SERVICE_JWT_PUBLIC_KEY_FILE"));
                if(!file.isAbsolute() || Files.size(file)>16384) throw new IllegalArgumentException();
                var key=RsaKeyConverters.x509().convert(new ByteArrayInputStream(Files.readAllBytes(file)));
                var verifier=NimbusJwtDecoder.withPublicKey(key).build();
                var defaults=JwtValidators.createDefaultWithIssuer(env.getRequiredProperty("BILLING_SERVICE_JWT_ISSUER"));
                verifier.setJwtValidator(jwt -> {
                    var result=defaults.validate(jwt);
                    if(result.hasErrors()) return result;
                    if(jwt.getIssuedAt()==null || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                            || jwt.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(60))
                            || jwt.getSubject()==null || jwt.getSubject().isBlank() || jwt.getId()==null || jwt.getId().isBlank()
                            || !jwt.getAudience().contains(env.getRequiredProperty("BILLING_SERVICE_JWT_AUDIENCE")))
                        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                    return OAuth2TokenValidatorResult.success();
                });
                return verifier.decode(token);
            } catch(Exception failure) { throw new BadJwtException("Service identity could not be confirmed"); }
        };
        http.securityMatcher("/internal/**").csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/v1/invoices/*/pdf")
                        .access((authentication,context) -> permitted(authentication.get(),env,"billing:invoice-pdf:read"))
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/v1/invoices/*/notification-summary")
                        .access((authentication,context) -> permitted(authentication.get(),env,"billing:notification-summary:read"))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.decoder(decoder)
                        .jwtAuthenticationConverter(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter())));
        return http.build();
    }
    private AuthorizationDecision permitted(org.springframework.security.core.Authentication identity,Environment env,String scope) {
        String worker=env.getProperty("BILLING_WORKER_SERVICE_SUBJECT","");
        return new AuthorizationDecision(identity.isAuthenticated() && !worker.isBlank() && worker.equals(identity.getName())
                && identity.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_"+scope)));
    }
}
