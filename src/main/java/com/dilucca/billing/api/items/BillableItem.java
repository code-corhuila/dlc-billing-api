package com.dilucca.billing.api.items;

import java.time.Instant;
import java.util.UUID;

/** Public financial projection; protected source narrative and audit identity stay private. */
public record BillableItem(UUID id, UUID patientId, UUID procedureId, String description,
        String quantity, String unitPrice, String currency, boolean approvalRequired,
        Instant createdAt, Instant updatedAt, int version, String status) { }
