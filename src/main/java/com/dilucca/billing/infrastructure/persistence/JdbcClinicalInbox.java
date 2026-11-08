package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.infrastructure.messaging.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Commit this receipt before acknowledging transport; never retain clinical reason. */
@org.springframework.stereotype.Service
public class JdbcClinicalInbox {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final ClinicalEventDecoder decoder=new ClinicalEventDecoder();
    public JdbcClinicalInbox(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public boolean receive(String body) {
        ClinicalFact fact=decoder.decode(body);
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))).execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(31705,hashtext(?))",(ResultSetExtractor<Void>) rows -> null,fact.eventId().toString());
            // Hash canonical original JSON in the database, discard its narrative immediately.
            String hash=jdbc.queryForObject("SELECT encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex')",String.class,body);
            var previous=jdbc.query("SELECT event_hash FROM billing.clinical_inbox WHERE event_id=?",(rs,row) -> rs.getString(1),fact.eventId());
            if(!previous.isEmpty()) {
                if(!previous.getFirst().equals(hash)) throw new Conflict();
                return false;
            }
            jdbc.update("INSERT INTO billing.clinical_inbox(event_id,event_type,event_hash,financial_fact) VALUES (?,?,?,?::jsonb)",
                    fact.eventId(),fact.eventType(),hash,mapper.writeValueAsString(fact));
            return true;
        });
    }
    public Optional<ClinicalFact> fact(UUID event) {
        return jdbc.query("SELECT financial_fact::text FROM billing.clinical_inbox WHERE event_id=?",(rs,row) -> restore(rs.getString(1)),event).stream().findFirst();
    }
    private ClinicalFact restore(String body) {
        var n=mapper.readTree(body); String type=n.get("eventType").stringValue();
        Class<? extends ClinicalFact.Payload> payload=switch(type) {
            case "ProcedureCompleted" -> ClinicalFact.Procedure.class;
            case "ConsultationRecorded" -> ClinicalFact.Consultation.class;
            case "ClinicalCareCompleted" -> ClinicalFact.Declaration.class;
            default -> throw new ClinicalEventDecoder.Invalid();
        };
        return new ClinicalFact(UUID.fromString(n.get("eventId").stringValue()),type,UUID.fromString(n.get("aggregateId").stringValue()),
                Instant.parse(n.get("occurredAt").stringValue()),mapper.readValue(n.get("trace").toString(),ClinicalFact.Trace.class),
                mapper.readValue(n.get("payload").toString(),payload));
    }
    public void markProjected(UUID event) {
        jdbc.update("UPDATE billing.clinical_inbox SET projected_at=greatest(clock_timestamp(),received_at) WHERE event_id=? AND projected_at IS NULL",event);
    }
    public static class Conflict extends RuntimeException { public Conflict() { super("Clinical event identity conflicts with stored fact"); } }
}
