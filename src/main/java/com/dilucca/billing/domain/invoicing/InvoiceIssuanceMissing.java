package com.dilucca.billing.domain.invoicing;

public final class InvoiceIssuanceMissing extends RuntimeException {
    public InvoiceIssuanceMissing() { super("Invoice not found"); }
}
