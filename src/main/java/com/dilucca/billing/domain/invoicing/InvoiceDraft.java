package com.dilucca.billing.domain.invoicing;

import java.util.*;

public record InvoiceDraft(UUID patientId, UUID appointmentId, List<UUID> itemIds) {
    public InvoiceDraft {
        Objects.requireNonNull(patientId);
        itemIds=List.copyOf(itemIds);
        if (itemIds.isEmpty() || new HashSet<>(itemIds).size()!=itemIds.size())
            throw new IllegalArgumentException("Unique billable sources required");
        itemIds=itemIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }
}
