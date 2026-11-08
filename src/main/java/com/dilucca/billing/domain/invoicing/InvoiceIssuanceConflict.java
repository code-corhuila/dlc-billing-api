package com.dilucca.billing.domain.invoicing;

public final class InvoiceIssuanceConflict extends RuntimeException {
    private final String code;
    public InvoiceIssuanceConflict(String code) { super("Invoice is not eligible for issuance"); this.code=code; }
    public String code() { return code; }
}
