package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.infrastructure.messaging.ClinicalFact;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Appends canonical facts inside the caller's financial transaction. */
final class BillingFactOutbox {
    private final JdbcTemplate jdbc; private final JsonMapper mapper;
    BillingFactOutbox(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    UUID append(String type,int version,UUID aggregate,String aggregateType,Instant now,Map<String,Object> payload,ClinicalFact.Trace trace,UUID cause) {
        UUID id=UUID.randomUUID(); Map<String,Object> metadata=new LinkedHashMap<>();
        metadata.put("correlationId",trace.correlationId()); metadata.put("causationId",cause); metadata.put("actorUserId",trace.actorUserId());
        var event=Map.of("eventId",id,"eventType",type,"aggregateId",aggregate,"aggregateType",aggregateType,
                "occurredAt",now.toString(),"version",version,"producer","billing-service","payload",payload,"metadata",metadata);
        jdbc.update("INSERT INTO billing.outbox_events(event_id,aggregate_id,event_type,event_version,occurred_at,event_body) VALUES (?,?,?,?,?,?::jsonb)",
                id,aggregate,type,version,Timestamp.from(now),mapper.writeValueAsString(event)); return id;
    }
}
