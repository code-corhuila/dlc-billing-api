package com.dilucca.billing.domain.pricing;

import java.math.BigDecimal;
import java.time.Instant;

public final class ProcedurePriceCatalog {

    private final String procedureCode;
    private final String name;
    private final BigDecimal basePrice;
    private final PriceValidity validity;

    public ProcedurePriceCatalog(
            String procedureCode,
            String name,
            String basePrice,
            Instant validFrom) {

        this.procedureCode = validateText(
                procedureCode, 80, "procedureCode");
        this.name = validateText(name, 255, "name");
        this.basePrice = ProcedurePrice.parseAmount(basePrice);
        this.validity = new PriceValidity(validFrom, null);
    }

    private static String validateText(
            String value, int maxLength, String field) {

        if (value == null || value.isBlank()
                || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1 to "
                            + maxLength + " characters");
        }

        return value;
    }

    public String procedureCode() {
        return procedureCode;
    }

    public String name() {
        return name;
    }

    public BigDecimal basePrice() {
        return basePrice;
    }

    public PriceValidity validity() {
        return validity;
    }
}
