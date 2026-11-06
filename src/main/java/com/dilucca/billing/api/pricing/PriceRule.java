package com.dilucca.billing.api.pricing;

import java.time.Instant;
import java.util.UUID;

public record PriceRule(UUID id, String code, String itemType, String unitPrice,
        boolean requiresApproval, Instant validFrom, Instant createdAt, Instant updatedAt, int version) { }
