package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.manual.ManualChargeInput;
import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.infrastructure.messaging.ClinicalFact;
import com.dilucca.billing.infrastructure.security.AppointmentFinalizationClient.Encounter;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** One local transaction resolves a proven source; no external write needs timeout inference. */
public final class JdbcManualChargeRecording {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Function<UUID,Encounter> encounters;
    private final BiPredicate<UUID,UUID> assignments;
    public JdbcManualChargeRecording(JdbcTemplate jdbc,JsonMapper mapper,Function<UUID,Encounter> encounters,
            BiPredicate<UUID,UUID> assignments) {
        this.jdbc=Objects.requireNonNull(jdbc); this.mapper=Objects.requireNonNull(mapper);
        this.encounters=Objects.requireNonNull(encounters); this.assignments=Objects.requireNonNull(assignments);
    }
    public CreatedResource record(UUID key,UUID actor,String role,ManualChargeInput input,UUID correlation) {
        Objects.requireNonNull(actor); Objects.requireNonNull(input); Objects.requireNonNull(correlation);
        if(!Set.of("ADMINISTRATOR","DENTIST").contains(role)) throw new Forbidden();
        // Current relationship authorization precedes every replay, including after care has closed.
        if(role.equals("DENTIST") && !assignments.test(input.patientId(),actor)) throw new Missing();
        return new JdbcIdempotentCreation(jdbc,mapper).execute(key,actor,"BillingRecordManualCharge","/api/v1/manual-charges",List.of(input),now -> {
            var matches=jdbc.query("SELECT c.clinical_record_id,c.consultation_id,c.dentist_id FROM billing.clinical_sources s "
                    +"JOIN billing.clinical_procedures p USING(procedure_id) JOIN billing.clinical_consultations c ON c.patient_id=p.patient_id AND c.appointment_id=p.appointment_id "
                    +"WHERE s.source_record_id=? AND p.procedure_id=? AND p.patient_id=? AND p.appointment_id=? AND s.item_type=? AND s.quantity=?",
                    (rs,row) -> new Source(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)),
                    input.sourceRecordId(),input.procedureId(),input.patientId(),input.appointmentId(),input.itemType(),input.quantity());
            if(matches.size()!=1) throw new Missing(); var source=matches.getFirst();
            if(role.equals("DENTIST") && !source.dentist().equals(actor)) throw new Missing();
            UUID charge=UUID.randomUUID(),write=UUID.randomUUID();
            try { new JdbcEncounterWriteCoordinator(jdbc,mapper).admit(source.record(),source.consultation(),write,charge); }
            catch(JdbcEncounterWriteCoordinator.Conflict frozen) { throw new Conflict(); }
            if(exists("SELECT EXISTS(SELECT FROM billing.clinical_declarations WHERE clinical_record_id=? AND consultation_id=?)",source.record(),source.consultation())
                    || exists("SELECT EXISTS(SELECT FROM billing.billable_items WHERE source_record_id=?)",input.sourceRecordId())) throw new Conflict();
            var care=encounters.apply(input.appointmentId());
            if(care==null || !input.appointmentId().equals(care.appointmentId()) || !input.patientId().equals(care.patientId())
                    || !source.dentist().equals(care.dentistId()) || !"EN_ATENCION".equals(care.status())) throw new Conflict();
            jdbc.update("INSERT INTO billing.encounter_accounts(encounter_account_id,clinical_record_id,consultation_id,patient_id,appointment_id) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                    UUID.randomUUID(),source.record(),source.consultation(),input.patientId(),input.appointmentId());
            var account=jdbc.queryForObject("SELECT encounter_account_id,patient_id,appointment_id,status FROM billing.encounter_accounts WHERE clinical_record_id=? AND consultation_id=? FOR UPDATE",
                    (rs,row) -> new Account(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4)),source.record(),source.consultation());
            if(!input.patientId().equals(account.patient()) || !input.appointmentId().equals(account.appointment()) || !"OPEN".equals(account.status())) throw new Conflict();
            Timestamp time=Timestamp.from(now);
            jdbc.update("INSERT INTO billing.billable_items(item_id,encounter_account_id,patient_id,appointment_id,procedure_id,source_record_id,item_type,description,quantity,unit_price,created_by,pricing_authority_role,justification,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    input.sourceRecordId(),account.id(),input.patientId(),input.appointmentId(),input.procedureId(),input.sourceRecordId(),input.itemType(),input.concept(),input.quantity(),input.unitPrice(),actor,role,input.justification(),time,time);
            jdbc.update("INSERT INTO billing.manual_charges(charge_id,item_id,source_record_id,procedure_id,patient_id,appointment_id,item_type,quantity,unit_price,currency,concept,justification,recorded_by,authority_role,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    charge,input.sourceRecordId(),input.sourceRecordId(),input.procedureId(),input.patientId(),input.appointmentId(),input.itemType(),input.quantity(),input.unitPrice(),input.currency(),input.concept(),input.justification(),actor,role,time);
            if(jdbc.update("UPDATE billing.manual_charge_admissions SET state='COMMITTED',resolved_at=? WHERE operation_id=? AND state='ADMITTED'",time,write)!=1) throw new Conflict();
            var payload=Map.<String,Object>of("chargeId",charge,"patientId",input.patientId(),"appointmentId",input.appointmentId(),"procedureId",input.procedureId(),
                    "sourceRecordId",input.sourceRecordId(),"recordedBy",actor,"recordedAt",now.toString(),"version",1);
            new BillingFactOutbox(jdbc,mapper).append("ManualChargeRecorded",1,charge,"ManualCharge",now,payload,new ClinicalFact.Trace(correlation,key,actor),key);
            Map<String,Object> response=new LinkedHashMap<>(payload); response.remove("chargeId"); response.put("id",charge);
            response.put("itemType",input.itemType()); response.put("quantity",input.quantity().toPlainString()); response.put("unitPrice",input.unitPrice().toPlainString());
            response.put("currency",input.currency()); response.put("concept",input.concept()); response.put("justification",input.justification()); response.put("status","READY");
            return new CreatedResource(201,"/api/v1/manual-charges/"+charge,mapper.writeValueAsString(response));
        });
    }
    private boolean exists(String sql,Object... parameters) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,parameters)); }
    private record Source(UUID record,UUID consultation,UUID dentist) { }
    private record Account(UUID id,UUID patient,UUID appointment,String status) { }
    public static class Missing extends RuntimeException { }
    public static class Conflict extends RuntimeException { public Conflict() { super("Manual charge source is no longer writable"); } }
    public static class Forbidden extends RuntimeException { }
}
