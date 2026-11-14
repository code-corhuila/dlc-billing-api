package com.dilucca.billing.api.pricing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Price(
        UUID id,
        String procedureCode,
        String name,
        String basePrice,
        String currency,
        Instant validFrom,
        Instant validTo,
        String status,
        Instant createdAt,
        Instant updatedAt,
        int version) {

    public Price {
        Objects.requireNonNull(id, "Id must not be null");
        Objects.requireNonNull(procedureCode, "Procedure code must not be null");
        Objects.requireNonNull(name, "Name must not be null");
        Objects.requireNonNull(basePrice, "Base price must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(status, "Status must not be null");

        if (procedureCode.isBlank() || procedureCode.length() > 80) {
            throw new IllegalArgumentException("Invalid procedure code");
        }

        if (name.isBlank() || name.length() > 255) {
            throw new IllegalArgumentException("Invalid procedure name");
        }

        if (!"COP".equals(currency)) {
            throw new IllegalArgumentException("Currency must be COP");
        }

        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("Invalid price status");
        }

        if (version < 1) {
            throw new IllegalArgumentException("Version must be positive");
        }
    }
}
