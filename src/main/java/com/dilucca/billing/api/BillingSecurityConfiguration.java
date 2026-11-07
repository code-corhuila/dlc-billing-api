package com.dilucca.billing.api;

import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
public class BillingSecurityConfiguration {

    @Bean
    SecurityFilterChain billingSecurityFilterChain(HttpSecurity http, IamSessionValidationClient iam)
            throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/procedure-prices")
                        .hasAnyRole(
                                "ADMINISTRATOR",
                                "DENTIST",
                                "SECRETARY_ASSISTANT")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/procedure-prices")
                        .hasRole("ADMINISTRATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/additional-price-rules")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/additional-price-rules")
                        .hasRole("ADMINISTRATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/billable-items")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.GET, "/api/v1/invoices", "/api/v1/invoices/*")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/invoices")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/invoices/*/issuances")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.GET, "/api/v1/invoices/*/payments")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.GET, "/api/v1/invoices/*/pdf")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/invoices/*/payments")
                        .hasAnyRole("ADMINISTRATOR", "SECRETARY_ASSISTANT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/manual-charges")
                        .hasAnyRole("ADMINISTRATOR", "DENTIST")
                        .anyRequest()
                        .denyAll())
                .addFilterAfter(new IamSessionEligibilityFilter(iam), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities =
                new JwtGrantedAuthoritiesConverter();

        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return converter;
    }
}
