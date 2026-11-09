package com.dilucca.billing.api;

import com.dilucca.billing.infrastructure.security.IamServiceTokenSupplier;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADR-020: with BILLING_SERVICE_CLIENT_SECRET_FILE set, Billing obtains its outbound service tokens from IAM by
 * client credentials; otherwise each client keeps reading its mounted token file.
 */
@Component
public class ServiceCredentials {
    private final String iamOrigin;
    private final String clientId;
    private final String secretFile;
    private final boolean localDevelopment;

    public ServiceCredentials(@Value("${BILLING_IAM_ORIGIN:}") String iamOrigin,
            @Value("${BILLING_SERVICE_CLIENT_ID:billing-api}") String clientId,
            @Value("${BILLING_SERVICE_CLIENT_SECRET_FILE:}") String secretFile,
            @Value("${BILLING_IAM_LOCAL_DEVELOPMENT:false}") boolean localDevelopment) {
        this.iamOrigin = iamOrigin;
        this.clientId = clientId;
        this.secretFile = secretFile;
        this.localDevelopment = localDevelopment;
    }

    /** The IAM-issued token source for one audience, or null when Billing uses token files. */
    public Supplier<String> forAudience(String audience, String scope) {
        if (secretFile.isBlank()) return null;
        return new IamServiceTokenSupplier(URI.create(iamOrigin), clientId, Path.of(secretFile), audience, scope,
            Duration.ofSeconds(3), Clock.systemUTC(), localDevelopment);
    }
}
