package com.dilucca.billing.infrastructure.persistence;

import java.util.*;
import java.util.function.BiPredicate;
import org.springframework.jdbc.core.JdbcTemplate;

/** Protected owner projection; never exposed through financial list/detail queries. */
public final class JdbcManualChargeQuery {
    private final JdbcTemplate jdbc;
    private final BiPredicate<UUID,UUID> assignments;
    public JdbcManualChargeQuery(JdbcTemplate jdbc,BiPredicate<UUID,UUID> assignments) {
        this.jdbc=Objects.requireNonNull(jdbc); this.assignments=Objects.requireNonNull(assignments);
    }
    public Map<String,Object> find(UUID charge,UUID actor,String role) {
        if(role==null || !Set.of("ADMINISTRATOR","DENTIST").contains(role)) throw new JdbcManualChargeRecording.Forbidden();
        var parameters=new ArrayList<Object>(List.of(charge));
        String scope="";
        if(role.equals("DENTIST")) {
            scope=" AND EXISTS(SELECT FROM billing.billable_items b JOIN billing.encounter_accounts e USING(encounter_account_id) "
                    +"JOIN billing.clinical_consultations c ON c.consultation_id=e.consultation_id AND c.clinical_record_id=e.clinical_record_id "
                    +"WHERE b.item_id=m.item_id AND c.dentist_id=?)";
            parameters.add(actor);
        }
        var rows=jdbc.query("SELECT m.* FROM billing.manual_charges m WHERE charge_id=?"+scope,(rs,row) -> {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",rs.getObject("charge_id",UUID.class));
            for(String field:List.of("patient","appointment","procedure")) result.put(field+"Id",rs.getObject(field+"_id",UUID.class));
            result.put("sourceRecordId",rs.getObject("source_record_id",UUID.class));
            result.put("itemType",rs.getString("item_type")); result.put("quantity",rs.getBigDecimal("quantity").toPlainString());
            result.put("unitPrice",rs.getBigDecimal("unit_price").toPlainString()); result.put("currency",rs.getString("currency"));
            result.put("concept",rs.getString("concept")); result.put("justification",rs.getString("justification"));
            result.put("recordedBy",rs.getObject("recorded_by",UUID.class)); result.put("recordedAt",rs.getTimestamp("recorded_at").toInstant().toString());
            result.put("status",rs.getString("status")); result.put("version",rs.getInt("version")); return result;
        },parameters.toArray());
        if(rows.isEmpty()) throw new JdbcManualChargeRecording.Missing();
        var result=rows.getFirst();
        if(role.equals("DENTIST") && !assignments.test((UUID)result.get("patientId"),actor)) throw new JdbcManualChargeRecording.Missing();
        return result;
    }
}
