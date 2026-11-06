package com.dilucca.billing.api.invoices;

import com.dilucca.billing.api.items.BillableItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Invoice(UUID id, UUID patientId, UUID appointmentId, String invoiceNumber,
        Instant issuedAt, List<BillableItem> items, String total, String balance,
        String currency, String status, Instant createdAt, Instant updatedAt, int version) {
    public Invoice withItems(List<BillableItem> values) {
        return new Invoice(id,patientId,appointmentId,invoiceNumber,issuedAt,List.copyOf(values),
                total,balance,currency,status,createdAt,updatedAt,version);
    }
}
