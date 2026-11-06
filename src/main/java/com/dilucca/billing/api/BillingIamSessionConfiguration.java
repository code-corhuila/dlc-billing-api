package com.dilucca.billing.api;

import com.dilucca.billing.infrastructure.security.IamSessionValidationClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BillingIamSessionConfiguration {
    @Bean
    IamSessionValidationClient iamSessionValidationClient(
            @Value("${BILLING_IAM_ORIGIN}") String origin,
            @Value("${BILLING_IAM_SERVICE_TOKEN_FILE}") String credentialFile,
            @Value("${BILLING_IAM_LOCAL_DEVELOPMENT:false}") boolean localDevelopment) {
        Path path = Path.of(credentialFile);
        if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute service credential path required");
        return new IamSessionValidationClient(URI.create(origin), () -> {
            try {
                if (Files.size(path) > 16384) throw new IllegalStateException();
                return Files.readString(path, StandardCharsets.UTF_8).strip();
            } catch (Exception unavailable) {
                throw new IllegalStateException("Service credential unavailable");
            }
        }, Duration.ofSeconds(3), localDevelopment);
    }
}
