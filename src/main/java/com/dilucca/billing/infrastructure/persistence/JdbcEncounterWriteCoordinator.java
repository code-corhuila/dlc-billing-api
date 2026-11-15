package com.dilucca.billing.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Shared local gate for ADR-017; no timeout ever proves a write aborted. */
@org.springframework.stereotype.Service
public class JdbcEncounterWriteCoordinator {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public JdbcEncounterWriteCoordinator(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public Settlement freezeCommand(UUID key,String service,UUID record,UUID consultation,UUID operation) {
        return command(key,service,record,consultation,operation,false);
    }
    public Settlement releaseCommand(UUID key,String service,UUID record,UUID consultation,UUID operation) {
        return command(key,service,record,consultation,operation,true);
    }
    private Settlement command(UUID key,String service,UUID record,UUID consultation,UUID operation,boolean release) {
        if(service==null || service.isBlank() || service.length()>200) throw new IllegalArgumentException();
        String path="/internal/v1/encounter-accounts/"+record+"/"+consultation+"/manual-charges/freezes";
        if(release) path+="/"+operation+"/releases";
        String resource=path;
        return transaction(() -> {
            new JdbcIdempotentCreation(jdbc,mapper).execute(key,"service:"+service,"BillingEncounter"+(release ? "Release" : "Freeze"),resource,List.of(operation),now -> {
                if(release) release(record,consultation,operation); else freeze(record,consultation,operation);
                // Persist the request binding, not a stale pendingWrites response.
                return new com.dilucca.billing.api.pricing.CreatedResource(200,resource,mapper.writeValueAsString(Map.of("operationId",operation)));
            });
            return release ? release(record,consultation,operation) : freeze(record,consultation,operation);
        });
    }
    private <T> T transaction(Supplier<T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
                .execute(status -> work.get());
    }
    private Gate gate(UUID record,UUID consultation,boolean create) {
        if(create) jdbc.update("INSERT INTO billing.encounter_write_gates(clinical_record_id,consultation_id) VALUES (?,?) ON CONFLICT DO NOTHING",record,consultation);
        return jdbc.query("SELECT freeze_state,freeze_operation_id,version FROM billing.encounter_write_gates WHERE clinical_record_id=? AND consultation_id=? FOR UPDATE",
                (rs,row) -> new Gate(rs.getString(1),rs.getObject(2,UUID.class),rs.getInt(3)),record,consultation)
                .stream().findFirst().orElseThrow(Missing::new);
    }
    public Settlement freeze(UUID record,UUID consultation,UUID operation) {
        return transaction(() -> {
            Gate gate=gate(record,consultation,true);
            var history=history(record,consultation,operation);
            if(history!=null && history.state().equals("RELEASED")) return mapper.readValue(history.snapshot(),Settlement.class);
            if(gate.state().equals("FROZEN")) {
                if(!operation.equals(gate.operation()) || history==null) throw new Conflict();
                return settlement(record,consultation,operation,gate);
            }
            if(history!=null || gate.version()==Integer.MAX_VALUE) throw new Conflict();
            jdbc.update("UPDATE billing.encounter_write_gates SET freeze_state='FROZEN',freeze_operation_id=?,version=version+1,updated_at=? WHERE clinical_record_id=? AND consultation_id=?",
                    operation,Timestamp.from(Instant.now()),record,consultation);
            jdbc.update("INSERT INTO billing.encounter_freezes(clinical_record_id,consultation_id,operation_id) VALUES (?,?,?)",record,consultation,operation);
            return settlement(record,consultation,operation,new Gate("FROZEN",operation,gate.version()+1));
        });
    }
    public Settlement release(UUID record,UUID consultation,UUID operation) {
        return transaction(() -> {
            // Serialize the absence check with source projection's declaration insert.
            jdbc.query("SELECT pg_advisory_xact_lock(31706,0)",(org.springframework.jdbc.core.ResultSetExtractor<Void>) rows -> null);
            Gate gate=gate(record,consultation,false);
            var previous=history(record,consultation,operation);
            if(previous==null) throw new Missing();
            if(previous.state().equals("RELEASED")) return mapper.readValue(previous.snapshot(),Settlement.class);
            if(previous.state().equals("COMMITTED") || !operation.equals(gate.operation()) || gate.version()==Integer.MAX_VALUE
                    || Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM billing.encounter_accounts WHERE clinical_record_id=? AND consultation_id=? AND status<>'OPEN') OR EXISTS(SELECT FROM billing.clinical_declarations WHERE clinical_record_id=? AND consultation_id=?)",Boolean.class,record,consultation,record,consultation)))
                throw new Conflict();
            jdbc.update("UPDATE billing.encounter_write_gates SET freeze_state='OPEN',freeze_operation_id=NULL,version=version+1,updated_at=? WHERE clinical_record_id=? AND consultation_id=?",
                    Timestamp.from(Instant.now()),record,consultation);
            var result=settlement(record,consultation,operation,new Gate("OPEN",null,gate.version()+1));
            jdbc.update("UPDATE billing.encounter_freezes SET state='RELEASED',settlement=?::jsonb WHERE clinical_record_id=? AND consultation_id=? AND operation_id=?",
                    mapper.writeValueAsString(result),record,consultation,operation);
            return result;
        });
    }
    /** Called after owner authorization; the later money transaction must resolve this durable admission. */
    public void admit(UUID record,UUID consultation,UUID write,UUID charge) {
        transaction(() -> {
            var gate=gate(record,consultation,true);
            if(!gate.state().equals("OPEN")) throw new Conflict();
            jdbc.update("INSERT INTO billing.manual_charge_admissions(operation_id,clinical_record_id,consultation_id,charge_id) VALUES (?,?,?,?)",write,record,consultation,charge);
            return null;
        });
    }
    private History history(UUID record,UUID consultation,UUID operation) {
        return jdbc.query("SELECT state,settlement::text FROM billing.encounter_freezes WHERE clinical_record_id=? AND consultation_id=? AND operation_id=?",
                (rs,row) -> new History(rs.getString(1),rs.getString(2)),record,consultation,operation).stream().findFirst().orElse(null);
    }
    private Settlement settlement(UUID record,UUID consultation,UUID operation,Gate gate) {
        // One statement snapshot: never omit a just-resolved charge while reporting pendingWrites=0.
        return jdbc.queryForObject("SELECT count(*) FILTER(WHERE state='ADMITTED'),COALESCE(array_agg(charge_id ORDER BY charge_id) FILTER(WHERE state='COMMITTED'),'{}'::uuid[]) "
                +"FROM billing.manual_charge_admissions WHERE clinical_record_id=? AND consultation_id=?",(rs,row) -> {
                    int pending=Math.toIntExact(rs.getLong(1));
                    var array=rs.getArray(2);
                    try { return new Settlement(operation,gate.state(),record,consultation,pending==0,pending,List.of((UUID[])array.getArray()),gate.version()); }
                    finally { array.free(); }
                },record,consultation);
    }
    private record Gate(String state,UUID operation,int version) { }
    private record History(String state,String snapshot) { }
    public record Settlement(UUID operationId,String freezeState,UUID clinicalRecordId,UUID consultationId,
            boolean settled,int pendingWrites,List<UUID> chargeIds,int version) { }
    public static class Conflict extends RuntimeException { public Conflict() { super("Encounter write coordination conflict"); } }
    public static class Missing extends RuntimeException { }
}
