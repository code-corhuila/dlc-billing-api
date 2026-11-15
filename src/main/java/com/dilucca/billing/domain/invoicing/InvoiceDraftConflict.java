package com.dilucca.billing.domain.invoicing;

public final class InvoiceDraftConflict extends RuntimeException {
    private final String code;
    public InvoiceDraftConflict(String code) {
        super("Invoice draft conflicts with its sources or saved request"); this.code=code;
    }
    public String code() { return code; }
}
