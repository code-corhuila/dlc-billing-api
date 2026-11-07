package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.infrastructure.messaging.ClinicalFact;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Resolve the fixed declared source set; an absent price/source stays pending, never zero. */
@org.springframework.stereotype.Service
public class JdbcClinicalChargeReconciliation {
    private final JdbcTemplate jdbc; private final JsonMapper mapper;
    public JdbcClinicalChargeReconciliation(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public boolean reconcile(UUID record,UUID consultation) {
        return new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
                .execute(status -> resolve(record,consultation));
    }
    private boolean resolve(UUID record,UUID consultation) {
        var declarations=jdbc.query("SELECT financial_fact::text FROM billing.clinical_declarations WHERE clinical_record_id=? AND consultation_id=?",(rs,row) -> mapper.readTree(rs.getString(1)),record,consultation);
        if(declarations.isEmpty()) return false;
        var fact=declarations.getFirst(); var d=mapper.readValue(fact.get("payload").toString(),ClinicalFact.Declaration.class);
        var trace=mapper.readValue(fact.get("trace").toString(),ClinicalFact.Trace.class);
        UUID cause=UUID.fromString(fact.get("eventId").stringValue());
        var gates=jdbc.query("SELECT freeze_operation_id FROM billing.encounter_write_gates WHERE clinical_record_id=? AND consultation_id=? AND freeze_state='FROZEN' FOR UPDATE",
                (rs,row) -> rs.getObject(1,UUID.class),record,consultation);
        if(gates.isEmpty()) return false;
        UUID operation=gates.getFirst();
        var settled=new JdbcEncounterWriteCoordinator(jdbc,mapper).freeze(record,consultation,operation);
        if(!settled.settled() || !new HashSet<>(settled.chargeIds()).equals(new HashSet<>(d.manualChargeIds()))) return false;
        jdbc.update("INSERT INTO billing.encounter_accounts(encounter_account_id,clinical_record_id,consultation_id,patient_id,appointment_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(),record,consultation,d.patientId(),d.appointmentId());
        var account=jdbc.queryForObject("SELECT encounter_account_id,patient_id,appointment_id,status FROM billing.encounter_accounts WHERE clinical_record_id=? AND consultation_id=? FOR UPDATE",
                (rs,row) -> new Account(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4)),record,consultation);
        if(!account.patient().equals(d.patientId()) || !account.appointment().equals(d.appointmentId())) throw new Conflict();
        if(account.status().equals("CHARGES_COMPLETED")) return exists("SELECT EXISTS(SELECT FROM billing.encounter_charge_receipts WHERE encounter_account_id=?)",account.id());
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        if(account.status().equals("OPEN")) {
            if(exists("SELECT EXISTS(SELECT FROM billing.billable_items b WHERE encounter_account_id=? AND NOT(procedure_id=ANY(?::uuid[])) AND NOT EXISTS(SELECT FROM billing.manual_charges m WHERE m.item_id=b.item_id AND m.charge_id=ANY(?::uuid[])))",
                    account.id(),array(d.procedureIds()),array(d.manualChargeIds()))) throw new Conflict();
            jdbc.update("UPDATE billing.encounter_accounts SET status='DECLARED',declared_procedure_ids=?::uuid[],declared_manual_charge_ids=?::uuid[],freeze_state='FROZEN',freeze_operation_id=?,version=version+1,updated_at=? WHERE encounter_account_id=?",
                    array(d.procedureIds()),array(d.manualChargeIds()),operation,Timestamp.from(now),account.id());
        }
        jdbc.update("UPDATE billing.encounter_freezes SET state='COMMITTED' WHERE clinical_record_id=? AND consultation_id=? AND operation_id=? AND state='ACTIVE'",record,consultation,operation);
        for(UUID procedure:d.procedureIds()) {
            var sources=jdbc.query("SELECT s.source_record_id,s.item_type,s.quantity,s.source_code,p.completed_at,p.patient_id,p.appointment_id FROM billing.clinical_sources s JOIN billing.clinical_procedures p USING(procedure_id) WHERE s.procedure_id=? ORDER BY s.source_record_id",
                    (rs,row) -> new Source(rs.getObject(1,UUID.class),rs.getString(2),rs.getBigDecimal(3),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getObject(6,UUID.class),rs.getObject(7,UUID.class)),procedure);
            for(var source:sources) {
                if(!source.patient().equals(d.patientId()) || !source.appointment().equals(d.appointmentId())) throw new Conflict();
                price(source,procedure,account.id(),trace.actorUserId(),now);
            }
            if(!sources.isEmpty() && !exists("SELECT EXISTS(SELECT FROM billing.clinical_sources s WHERE procedure_id=? AND NOT EXISTS(SELECT FROM billing.billable_items b WHERE b.source_record_id=s.source_record_id AND b.encounter_account_id=? AND NOT b.approval_required))",procedure,account.id()))
                procedureReceipt(procedure,account.id(),d,trace,cause,now);
        }
        long receipts=jdbc.queryForObject("SELECT count(*) FROM billing.procedure_charge_receipts WHERE encounter_account_id=? AND procedure_id=ANY(?::uuid[])",Long.class,account.id(),array(d.procedureIds()));
        long manuals=jdbc.queryForObject("SELECT count(*) FROM billing.manual_charges m JOIN billing.billable_items b ON b.item_id=m.item_id WHERE m.charge_id=ANY(?::uuid[]) AND m.status='READY' AND b.encounter_account_id=?",Long.class,array(d.manualChargeIds()),account.id());
        if(receipts!=d.procedureIds().size() || manuals!=d.manualChargeIds().size()) return false;
        var items=items(account.id(),null); var total=total(account.id(),null);
        jdbc.update("UPDATE billing.encounter_accounts SET status='CHARGES_COMPLETED',version=version+1,updated_at=? WHERE encounter_account_id=? AND status='DECLARED'",Timestamp.from(now),account.id());
        Map<String,Object> payload=new LinkedHashMap<>(Map.of("encounterAccountId",account.id(),"clinicalRecordId",record,"consultationId",consultation,
                "patientId",d.patientId(),"appointmentId",d.appointmentId(),"procedureIds",d.procedureIds(),"manualChargeIds",d.manualChargeIds(),"billableItemIds",items,"total",total,"currency","COP"));
        payload.put("completedAt",now.toString());
        UUID event=new BillingFactOutbox(jdbc,mapper).append("EncounterChargesCompleted",1,account.id(),"EncounterAccount",now,payload,trace,cause);
        jdbc.update("INSERT INTO billing.encounter_charge_receipts(encounter_account_id,event_id) VALUES (?,?)",account.id(),event);
        return true;
    }
    private void price(Source s,UUID procedure,UUID account,UUID actor,Instant now) {
        if(exists("SELECT EXISTS(SELECT FROM billing.billable_items WHERE source_record_id=?)",s.id())) {
            if(!exists("SELECT EXISTS(SELECT FROM billing.billable_items WHERE source_record_id=? AND encounter_account_id=? AND procedure_id=? AND quantity=?)",s.id(),account,procedure,s.quantity())) throw new Conflict();
            return; // Preserve an existing justified manual price, including its exact concept.
        }
        if(actor==null || s.code()==null) return;
        String sql=s.type().equals("BASE_PROCEDURE")
                ? "SELECT base_price,name,version,FALSE FROM billing.procedure_prices WHERE procedure_code=? AND status='ACTIVE' AND currency='COP' AND valid_from<=? AND(valid_to IS NULL OR valid_to>?)"
                : "SELECT unit_price,rule_code,version,requires_approval FROM billing.additional_price_rules WHERE rule_code=? AND status='ACTIVE' AND currency='COP' AND valid_from<=? AND(valid_to IS NULL OR valid_to>?) AND item_type=?";
        var parameters=new ArrayList<Object>(List.of(s.code(),Timestamp.from(s.completed()),Timestamp.from(s.completed())));
        if(!s.type().equals("BASE_PROCEDURE")) parameters.add(s.type());
        var prices=jdbc.query(sql,(rs,row) -> new Price(rs.getBigDecimal(1),rs.getString(2),rs.getInt(3),rs.getBoolean(4)),parameters.toArray());
        if(prices.isEmpty()) return; if(prices.size()!=1) throw new Conflict(); var p=prices.getFirst();
        jdbc.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,item_type,description,quantity,unit_price,catalog_version,created_by,created_at,updated_at,approval_required) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                s.id(),account,s.patient(),s.appointment(),procedure,s.id(),s.type(),p.description(),s.quantity(),p.amount(),p.version(),actor,Timestamp.from(now),Timestamp.from(now),p.approval());
    }
    private void procedureReceipt(UUID procedure,UUID account,ClinicalFact.Declaration d,ClinicalFact.Trace trace,UUID cause,Instant now) {
        var previous=jdbc.query("SELECT encounter_account_id FROM billing.procedure_charge_receipts WHERE procedure_id=?",(rs,row) -> rs.getObject(1,UUID.class),procedure);
        if(!previous.isEmpty()) { if(!previous.getFirst().equals(account)) throw new Conflict(); return; }
        var payload=Map.<String,Object>of("clinicalRecordId",d.clinicalRecordId(),"consultationId",d.consultationId(),"procedureId",procedure,
                "appointmentId",d.appointmentId(),"patientId",d.patientId(),"billableItemIds",items(account,procedure),"total",total(account,procedure),"currency","COP","createdAt",now.toString());
        UUID event=new BillingFactOutbox(jdbc,mapper).append("BillableItemsCreated",2,account,"EncounterAccount",now,payload,trace,cause);
        jdbc.update("INSERT INTO billing.procedure_charge_receipts(procedure_id,encounter_account_id,event_id) VALUES (?,?,?)",procedure,account,event);
    }
    private List<UUID> items(UUID account,UUID procedure) {
        return jdbc.query("SELECT item_id FROM billing.billable_items WHERE encounter_account_id=?"+(procedure==null ? "" : " AND procedure_id=?")+" ORDER BY item_id",
                (rs,row) -> rs.getObject(1,UUID.class),procedure==null ? new Object[]{account} : new Object[]{account,procedure});
    }
    private String total(UUID account,UUID procedure) {
        return jdbc.queryForObject("SELECT COALESCE(sum(line_total),0) FROM billing.billable_items WHERE encounter_account_id=?"+(procedure==null ? "" : " AND procedure_id=?"),
                BigDecimal.class,procedure==null ? new Object[]{account} : new Object[]{account,procedure}).setScale(2).toPlainString();
    }
    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private String array(List<UUID> values) { return "{"+String.join(",",values.stream().map(UUID::toString).toList())+"}"; }
    private record Account(UUID id,UUID patient,UUID appointment,String status) { }
    private record Source(UUID id,String type,BigDecimal quantity,String code,Instant completed,UUID patient,UUID appointment) { }
    private record Price(BigDecimal amount,String description,int version,boolean approval) { }
    public static class Conflict extends RuntimeException { public Conflict() { super("Declared financial sources conflict with stored facts"); } }
}
