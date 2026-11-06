package com.dilucca.billing.api.pricing;

public final class PriceCreationConflict extends RuntimeException {
    private final String code;

    public PriceCreationConflict(String code) {
        super("Price creation conflicts with the saved request or current validity");
        this.code = code;
    }

    public String code() { return code; }
}
