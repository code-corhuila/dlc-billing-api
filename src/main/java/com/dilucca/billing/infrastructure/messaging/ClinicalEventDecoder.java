package com.dilucca.billing.infrastructure.messaging;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Strict official v1 envelopes, plus owner semantic correlation and source uniqueness. */
public final class ClinicalEventDecoder {
    private final JsonMapper parser=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ClinicalFact decode(String body) {
        try {
            if(body==null || body.getBytes(StandardCharsets.UTF_8).length>262144) throw new Invalid();
            var n=parser.readTree(body);
            shape(n,"eventId,eventType,aggregateId,aggregateType,occurredAt,version,producer,payload,metadata","");
            if(!text(n,"producer").equals("clinical-service") || number(n,"version")!=1) throw new Invalid();
            String type=text(n,"eventType"); UUID aggregate=uuid(n,"aggregateId");
            var metadata=n.get("metadata"); shape(metadata,"correlationId,causationId,actorUserId","");
            var trace=new ClinicalFact.Trace(uuid(metadata,"correlationId"),uuid(metadata,"causationId"),
                    metadata.get("actorUserId").isNull() ? null : uuid(metadata,"actorUserId"));
            var p=n.get("payload");
            ClinicalFact.Payload payload=switch(type) {
                case "ProcedureCompleted" -> procedure(p,aggregate);
                case "ConsultationRecorded" -> consultation(p,aggregate);
                case "ClinicalCareCompleted" -> declaration(p,aggregate);
                default -> throw new Invalid();
            };
            if(!text(n,"aggregateType").equals(payload instanceof ClinicalFact.Procedure ? "Treatment" : "ClinicalRecord")) throw new Invalid();
            return new ClinicalFact(uuid(n,"eventId"),type,aggregate,time(n,"occurredAt"),trace,payload);
        } catch(Exception invalid) { throw new Invalid(); }
    }
    private ClinicalFact.Procedure procedure(JsonNode p,UUID aggregate) {
        shape(p,"procedureId,treatmentId,patientId,appointmentId,procedureCode,additionalItems,completedAt","");
        UUID procedure=uuid(p,"procedureId"),treatment=uuid(p,"treatmentId");
        if(!aggregate.equals(treatment) || !p.get("additionalItems").isArray()) throw new Invalid();
        var extras=new ArrayList<ClinicalFact.Extra>(); var sources=new HashSet<UUID>(); sources.add(procedure);
        for(var extra:p.get("additionalItems")) {
            shape(extra,"type,quantity,clinicalReason,sourceRecordId","code");
            String type=text(extra,"type"),quantity=text(extra,"quantity");
            if(!Set.of("ADDITIONAL_MATERIAL","ADDITIONAL_PROCEDURE","COMPLEXITY_ADJUSTMENT","OTHER_AUTHORIZED_CHARGE").contains(type)
                    || !quantity.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?")) throw new Invalid();
            var exact=new BigDecimal(quantity); if(exact.signum()<=0) throw new Invalid();
            UUID source=uuid(extra,"sourceRecordId"); if(!sources.add(source)) throw new Invalid();
            text(extra,"clinicalReason"); // Never retained in financial facts, responses or logs.
            extras.add(new ClinicalFact.Extra(type,extra.has("code") ? text(extra,"code") : null,exact,source));
        }
        return new ClinicalFact.Procedure(procedure,treatment,uuid(p,"patientId"),uuid(p,"appointmentId"),text(p,"procedureCode"),extras,time(p,"completedAt"));
    }
    private ClinicalFact.Consultation consultation(JsonNode p,UUID aggregate) {
        shape(p,"consultationId,patientId,dentistId,recordedAt","appointmentId");
        return new ClinicalFact.Consultation(aggregate,uuid(p,"consultationId"),uuid(p,"patientId"),
                p.has("appointmentId") ? uuid(p,"appointmentId") : null,uuid(p,"dentistId"),time(p,"recordedAt"));
    }
    private ClinicalFact.Declaration declaration(JsonNode p,UUID aggregate) {
        shape(p,"clinicalRecordId,consultationId,patientId,appointmentId,dentistId,procedureIds,manualChargeIds,completedAt,clinicalVersion","");
        if(!uuid(p,"clinicalRecordId").equals(aggregate)) throw new Invalid();
        return new ClinicalFact.Declaration(aggregate,uuid(p,"consultationId"),uuid(p,"patientId"),uuid(p,"appointmentId"),uuid(p,"dentistId"),
                identifiers(p.get("procedureIds")),identifiers(p.get("manualChargeIds")),time(p,"completedAt"),number(p,"clinicalVersion"));
    }
    private List<UUID> identifiers(JsonNode values) {
        if(!values.isArray()) throw new Invalid();
        var result=new ArrayList<UUID>(); var seen=new HashSet<UUID>();
        for(var value:values) { UUID id=identifier(value); if(!seen.add(id)) throw new Invalid(); result.add(id); }
        return result;
    }
    private void shape(JsonNode n,String required,String optional) {
        if(n==null || !n.isObject()) throw new Invalid();
        var keys=required.split(","); int expected=keys.length;
        for(String key:keys) if(!n.has(key)) throw new Invalid();
        if(!optional.isEmpty()) for(String key:optional.split(",")) if(n.has(key)) expected++;
        if(n.size()!=expected) throw new Invalid();
    }
    private String text(JsonNode n,String key) {
        var value=n.get(key); if(value==null || !value.isString() || value.stringValue().isEmpty()) throw new Invalid(); return value.stringValue();
    }
    private long number(JsonNode n,String key) {
        var value=n.get(key);
        if(value==null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<1) throw new Invalid(); return value.longValue();
    }
    private UUID uuid(JsonNode n,String key) { return identifier(n.get(key)); }
    private UUID identifier(JsonNode n) {
        if(n==null || !n.isString()) throw new Invalid();
        UUID id=UUID.fromString(n.stringValue()); if(!id.toString().equalsIgnoreCase(n.stringValue())) throw new Invalid(); return id;
    }
    private Instant time(JsonNode n,String key) { return Instant.parse(text(n,key)); }
    public static class Invalid extends RuntimeException { public Invalid() { super("Invalid clinical event"); } }
}
