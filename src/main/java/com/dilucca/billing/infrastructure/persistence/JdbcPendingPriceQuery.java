package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.PaginatedMeta;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Encounter accounts still DECLARED because a declared source has no billable item: no price or rule was valid at
 * its completion time (ADR-015 decision 6). Only item type, code and quantity are read; never clinical narrative.
 */
@org.springframework.stereotype.Repository
public class JdbcPendingPriceQuery {
    private static final String MISSING = """
            FROM billing.encounter_accounts e
            JOIN billing.clinical_declarations d
              ON d.clinical_record_id=e.clinical_record_id AND d.consultation_id=e.consultation_id
            JOIN billing.clinical_sources s ON s.procedure_id=ANY(e.declared_procedure_ids)
            JOIN billing.clinical_procedures p ON p.procedure_id=s.procedure_id
            WHERE e.status='DECLARED'
              AND NOT EXISTS(SELECT FROM billing.billable_items b WHERE b.source_record_id=s.source_record_id)
            """;
    private final JdbcTemplate jdbc;

    public JdbcPendingPriceQuery(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    public Page list(int page,int limit) {
        long total=Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(DISTINCT e.encounter_account_id) "+MISSING,Long.class));
        var rows=jdbc.query("""
                WITH accounts AS (
                  SELECT DISTINCT e.encounter_account_id,e.patient_id,e.appointment_id,d.completed_at AS declared_at
                """+MISSING+"""
                  ORDER BY declared_at,encounter_account_id LIMIT ? OFFSET ?)
                SELECT a.encounter_account_id,a.patient_id,a.appointment_id,a.declared_at,
                       s.source_record_id,s.procedure_id,s.item_type,coalesce(s.source_code,'') AS code,s.quantity,
                       p.completed_at
                FROM accounts a
                JOIN billing.encounter_accounts e ON e.encounter_account_id=a.encounter_account_id
                JOIN billing.clinical_sources s ON s.procedure_id=ANY(e.declared_procedure_ids)
                JOIN billing.clinical_procedures p ON p.procedure_id=s.procedure_id
                WHERE NOT EXISTS(SELECT FROM billing.billable_items b WHERE b.source_record_id=s.source_record_id)
                ORDER BY a.declared_at,a.encounter_account_id,p.completed_at,s.source_record_id
                """,(rs,row) -> new Row(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant(),new Source(rs.getObject(5,UUID.class),
                rs.getObject(6,UUID.class),rs.getString(7),rs.getString(8),
                rs.getBigDecimal(9).setScale(4,RoundingMode.UNNECESSARY).toPlainString(),
                rs.getTimestamp(10).toInstant())),limit,(long)(page-1)*limit);
        Map<UUID,Account> accounts=new LinkedHashMap<>();
        for (Row r:rows) {
            accounts.computeIfAbsent(r.account(),id -> new Account(id,r.patient(),r.appointment(),r.declaredAt(),
                    new ArrayList<>())).missingSources().add(r.source());
        }
        return new Page(List.copyOf(accounts.values()),new PaginatedMeta(page,limit,total,
                Math.toIntExact(total/limit+(total%limit==0 ? 0 : 1))));
    }

    public record Page(List<Account> data,PaginatedMeta meta) { }
    public record Account(UUID encounterAccountId,UUID patientId,UUID appointmentId,Instant declaredAt,
            List<Source> missingSources) { }
    public record Source(UUID sourceRecordId,UUID procedureId,String itemType,String code,String quantity,
            Instant completedAt) { }
    private record Row(UUID account,UUID patient,UUID appointment,Instant declaredAt,Source source) { }
}
