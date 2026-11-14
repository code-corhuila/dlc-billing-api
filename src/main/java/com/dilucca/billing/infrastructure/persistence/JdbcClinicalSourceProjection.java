package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.infrastructure.messaging.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Source projections and inbox commit atomically; no placeholder prices or cross-owner reads. */
@org.springframework.stereotype.Service
public class JdbcClinicalSourceProjection {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public JdbcClinicalSourceProjection(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public void ingest(String body) {
        var fact=new ClinicalEventDecoder().decode(body);
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()))).executeWithoutResult(status -> {
            // Coarse owner-local serialization also protects overlapping declaration source sets.
            jdbc.query("SELECT pg_advisory_xact_lock(31706,0)",(ResultSetExtractor<Void>) rows -> null);
            var inbox=new JdbcClinicalInbox(jdbc,mapper); inbox.receive(body);
            String json=mapper.writeValueAsString(fact),hash=hash(fact.payload());
            switch(fact.payload()) {
                case ClinicalFact.Procedure p -> procedure(p,hash,json);
                case ClinicalFact.Consultation c -> consultation(c,hash,json);
                case ClinicalFact.Declaration d -> declaration(d,hash,json);
            }
            inbox.markProjected(fact.eventId());
        });
    }
    private String hash(Object value) {
        return jdbc.queryForObject("SELECT encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex')",String.class,mapper.writeValueAsString(value));
    }
    private void procedure(ClinicalFact.Procedure p,String hash,String json) {
        reject("SELECT EXISTS(SELECT FROM billing.clinical_declarations WHERE ?=ANY(procedure_ids) AND (patient_id<>? OR appointment_id<>?))",
                p.procedureId(),p.patientId(),p.appointmentId());
        jdbc.update("INSERT INTO billing.clinical_procedures(procedure_id,treatment_id,patient_id,appointment_id,procedure_code,completed_at,fact_hash,financial_fact) "
                +"VALUES (?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",p.procedureId(),p.treatmentId(),p.patientId(),p.appointmentId(),p.procedureCode(),Timestamp.from(p.completedAt()),hash,json);
        same("clinical_procedures","procedure_id=?",hash,p.procedureId());
        source(p.procedureId(),p.procedureId(),"BASE_PROCEDURE",java.math.BigDecimal.ONE,p.procedureCode());
        for(var e:p.extras()) source(e.sourceRecordId(),p.procedureId(),e.type(),e.quantity(),e.code());
    }
    private void source(UUID source,UUID procedure,String type,java.math.BigDecimal quantity,String code) {
        jdbc.update("INSERT INTO billing.clinical_sources(source_record_id,procedure_id,item_type,quantity,source_code) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",source,procedure,type,quantity,code);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM billing.clinical_sources WHERE source_record_id=? AND procedure_id=? AND item_type=? AND quantity=? AND source_code IS NOT DISTINCT FROM ?)",
                Boolean.class,source,procedure,type,quantity,code))) throw new Conflict();
    }
    private void consultation(ClinicalFact.Consultation c,String hash,String json) {
        reject("SELECT EXISTS(SELECT FROM billing.clinical_declarations WHERE consultation_id=? AND (clinical_record_id<>? OR patient_id<>? OR dentist_id<>?))",
                c.consultationId(),c.clinicalRecordId(),c.patientId(),c.dentistId());
        if(c.appointmentId()!=null) reject("SELECT EXISTS(SELECT FROM billing.clinical_declarations WHERE consultation_id=? AND appointment_id<>?)",c.consultationId(),c.appointmentId());
        jdbc.update("INSERT INTO billing.clinical_consultations(consultation_id,clinical_record_id,patient_id,appointment_id,dentist_id,recorded_at,fact_hash,financial_fact) "
                +"VALUES (?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",c.consultationId(),c.clinicalRecordId(),c.patientId(),c.appointmentId(),c.dentistId(),Timestamp.from(c.recordedAt()),hash,json);
        same("clinical_consultations","consultation_id=?",hash,c.consultationId());
    }
    private void declaration(ClinicalFact.Declaration d,String hash,String json) {
        reject("SELECT EXISTS(SELECT FROM billing.clinical_consultations WHERE consultation_id=? AND (clinical_record_id<>? OR patient_id<>? OR dentist_id<>? OR (appointment_id IS NOT NULL AND appointment_id<>?)))",
                d.consultationId(),d.clinicalRecordId(),d.patientId(),d.dentistId(),d.appointmentId());
        reject("SELECT EXISTS(SELECT FROM billing.clinical_procedures WHERE procedure_id=ANY(?::uuid[]) AND (patient_id<>? OR appointment_id<>?))",
                array(d.procedureIds()),d.patientId(),d.appointmentId());
        reject("SELECT EXISTS(SELECT FROM billing.clinical_declarations WHERE (procedure_ids && ?::uuid[] OR manual_charge_ids && ?::uuid[]) AND (clinical_record_id<>? OR consultation_id<>?))",
                array(d.procedureIds()),array(d.manualChargeIds()),d.clinicalRecordId(),d.consultationId());
        jdbc.update("INSERT INTO billing.clinical_declarations(clinical_record_id,consultation_id,patient_id,appointment_id,dentist_id,procedure_ids,manual_charge_ids,completed_at,clinical_version,fact_hash,financial_fact) "
                +"VALUES (?,?,?,?,?,?::uuid[],?::uuid[],?,?,?,?::jsonb) ON CONFLICT DO NOTHING",d.clinicalRecordId(),d.consultationId(),d.patientId(),d.appointmentId(),d.dentistId(),
                array(d.procedureIds()),array(d.manualChargeIds()),Timestamp.from(d.completedAt()),d.clinicalVersion(),hash,json);
        same("clinical_declarations","clinical_record_id=? AND consultation_id=?",hash,d.clinicalRecordId(),d.consultationId());
    }
    private String array(List<UUID> values) { return "{"+String.join(",",values.stream().map(UUID::toString).toList())+"}"; }
    private void reject(String sql,Object... values) { if(Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,values))) throw new Conflict(); }
    private void same(String table,String predicate,String hash,Object... keys) {
        // Table/predicate are compile-time owner constants, never message input.
        if(!hash.equals(jdbc.queryForObject("SELECT fact_hash FROM billing."+table+" WHERE "+predicate,String.class,keys))) throw new Conflict();
    }
    public static class Conflict extends RuntimeException { public Conflict() { super("Clinical source correlation conflicts with stored facts"); } }
}
