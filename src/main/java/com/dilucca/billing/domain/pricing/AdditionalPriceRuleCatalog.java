package com.dilucca.billing.domain.pricing;

import java.time.Instant;
import java.util.Set;

public record AdditionalPriceRuleCatalog(String code, String itemType, String unitPrice, Instant validFrom) {
    private static final Set<String> TYPES = Set.of("ADDITIONAL_MATERIAL", "ADDITIONAL_PROCEDURE",
            "COMPLEXITY_ADJUSTMENT", "OTHER_AUTHORIZED_CHARGE");

    public AdditionalPriceRuleCatalog {
        if (code == null || code.isBlank() || code.length() > 80
                || itemType == null || !TYPES.contains(itemType) || validFrom == null) {
            throw new IllegalArgumentException("Invalid additional price rule");
        }
        unitPrice = ProcedurePrice.parseAmount(unitPrice).toPlainString();
    }
}
