package com.dilucca.billing.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Minimized owner facts; clinical narrative is validated then discarded. */
public record ClinicalFact(UUID eventId,String eventType,UUID aggregateId,Instant occurredAt,Trace trace,Payload payload) {
    public sealed interface Payload permits Procedure,Consultation,Declaration { }
    public record Trace(UUID correlationId,UUID causationId,UUID actorUserId) { }
    public record Extra(String type,String code,BigDecimal quantity,UUID sourceRecordId) { }
    public record Procedure(UUID procedureId,UUID treatmentId,UUID patientId,UUID appointmentId,
            String procedureCode,List<Extra> extras,Instant completedAt) implements Payload {
        public Procedure { extras=List.copyOf(extras); }
    }
    public record Consultation(UUID clinicalRecordId,UUID consultationId,UUID patientId,UUID appointmentId,
            UUID dentistId,Instant recordedAt) implements Payload { }
    public record Declaration(UUID clinicalRecordId,UUID consultationId,UUID patientId,UUID appointmentId,
            UUID dentistId,List<UUID> procedureIds,List<UUID> manualChargeIds,Instant completedAt,long clinicalVersion) implements Payload {
        public Declaration { procedureIds=List.copyOf(procedureIds); manualChargeIds=List.copyOf(manualChargeIds); }
    }
}
