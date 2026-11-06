package com.dilucca.billing.domain.invoicing;

public final class InvoiceDraftUnavailable extends RuntimeException {
    public InvoiceDraftUnavailable() { super("Appointment validation unavailable"); }
}
