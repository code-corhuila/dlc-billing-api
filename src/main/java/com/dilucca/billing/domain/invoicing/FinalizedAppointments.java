package com.dilucca.billing.domain.invoicing;

import java.util.UUID;

/** Authoritative owner read; returns the verified terminal appointment version. */
@FunctionalInterface
public interface FinalizedAppointments {
    long requireFinalized(UUID appointmentId, UUID patientId);
}
