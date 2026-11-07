package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.infrastructure.messaging.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Durable source facts precede money; retries reconcile again after a lost acknowledgement. */
public final class JdbcClinicalEventHandling {
    private final JdbcTemplate jdbc;
    private final JdbcClinicalSourceProjection projection;
    private final JdbcClinicalChargeReconciliation reconciliation;
    private Pair cursor;
    public JdbcClinicalEventHandling(JdbcTemplate jdbc,JsonMapper mapper) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.projection=new JdbcClinicalSourceProjection(jdbc,mapper);
        this.reconciliation=new JdbcClinicalChargeReconciliation(jdbc,mapper);
    }
    public void receive(String body) {
        ClinicalFact fact;
        try {
            fact=new ClinicalEventDecoder().decode(body);
            projection.ingest(body);
        } catch(ClinicalEventDecoder.Invalid | JdbcClinicalInbox.Conflict | JdbcClinicalSourceProjection.Conflict invalid) {
            throw new Permanent();
        }
        List<Pair> affected=switch(fact.payload()) {
            case ClinicalFact.Declaration d -> List.of(new Pair(d.clinicalRecordId(),d.consultationId()));
            case ClinicalFact.Consultation c -> pairs("SELECT clinical_record_id,consultation_id FROM billing.clinical_declarations WHERE clinical_record_id=? AND consultation_id=?",c.clinicalRecordId(),c.consultationId());
            case ClinicalFact.Procedure p -> pairs("SELECT clinical_record_id,consultation_id FROM billing.clinical_declarations WHERE ?=ANY(procedure_ids)",p.procedureId());
        };
        for(var pair:affected) reconciliation.reconcile(pair.record(),pair.consultation());
    }
    /** Keyset cursor visits blocked sources fairly; it is only a scheduling hint, never financial state. */
    public synchronized int sweep(int batch) {
        if(batch<1 || batch>100) throw new IllegalArgumentException();
        var pending=pairs("SELECT d.clinical_record_id,d.consultation_id FROM billing.clinical_declarations d "
                +"WHERE NOT EXISTS(SELECT FROM billing.encounter_accounts a JOIN billing.encounter_charge_receipts r USING(encounter_account_id) "
                +"WHERE a.clinical_record_id=d.clinical_record_id AND a.consultation_id=d.consultation_id) "
                +"AND (?::uuid IS NULL OR (d.clinical_record_id,d.consultation_id)>(?::uuid,?::uuid)) "
                +"ORDER BY d.clinical_record_id,d.consultation_id LIMIT ?",cursor==null ? null : cursor.record(),
                cursor==null ? null : cursor.record(),cursor==null ? null : cursor.consultation(),batch);
        if(pending.isEmpty()) { cursor=null; return 0; }
        boolean failed=false;
        for(var pair:pending) {
            cursor=pair;
            try { reconciliation.reconcile(pair.record(),pair.consultation()); }
            catch(RuntimeException unavailable) { failed=true; }
        }
        if(failed) throw new Unavailable();
        return pending.size();
    }
    private List<Pair> pairs(String sql,Object... parameters) {
        return jdbc.query(sql,(rs,row) -> new Pair(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),parameters);
    }
    private record Pair(UUID record,UUID consultation) { }
    public static class Permanent extends RuntimeException { public Permanent() { super("Invalid clinical source fact"); } }
    public static class Unavailable extends RuntimeException { public Unavailable() { super("Clinical reconciliation pass unavailable"); } }
}
