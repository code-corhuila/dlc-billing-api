package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.api.pricing.PriceCreationConflict;
import com.dilucca.billing.domain.invoicing.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** All financial state, event intent, audit and replay commit together; no broker call. */
@org.springframework.stereotype.Service
public final class JdbcInvoiceIssuance {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public JdbcInvoiceIssuance(JdbcTemplate jdbc,JsonMapper mapper) {
        this.jdbc=Objects.requireNonNull(jdbc); this.mapper=Objects.requireNonNull(mapper);
    }

    public CreatedResource issue(UUID key,UUID actor,UUID invoiceId,long expectedVersion,UUID correlationId) {
        Objects.requireNonNull(invoiceId); Objects.requireNonNull(correlationId);
        if (expectedVersion<1) throw new IllegalArgumentException();
        try {
            return new JdbcIdempotentCreation(jdbc,mapper).execute(key,actor,"BillingIssueinvoice",
                    "/api/v1/invoices/"+invoiceId+"/issuances",List.of(expectedVersion),now -> {
                var headers=jdbc.query("SELECT status,version,total FROM billing.invoices WHERE invoice_id=? FOR UPDATE",
                        (rs,row) -> new Header(rs.getString("status"),rs.getInt("version"),rs.getBigDecimal("total")),invoiceId);
                if (headers.isEmpty()) throw new InvoiceIssuanceMissing();
                var header=headers.getFirst();
                if (!header.status().equals("DRAFT") || header.version()!=expectedVersion
                        || header.version()==Integer.MAX_VALUE) throw conflict();
                var sources=jdbc.query("""
                        SELECT b.line_total,(b.status='BILLED' AND e.status='CHARGES_COMPLETED'
                          AND (b.procedure_id=ANY(e.declared_procedure_ids)
                               OR b.source_record_id=ANY(e.declared_manual_charge_ids))
                          AND (b.catalog_version IS NOT NULL OR (b.pricing_authority_role IN ('DENTIST','ADMINISTRATOR')
                               AND length(btrim(b.justification))>0))
                          AND EXISTS(SELECT FROM billing.invoice_appointment_validations v
                               WHERE v.invoice_id=i.invoice_id AND v.patient_id=b.patient_id
                               AND v.appointment_id=b.appointment_id)) IS TRUE AS eligible
                        FROM billing.invoice_items i JOIN billing.billable_items b ON b.item_id=i.item_id
                        JOIN billing.encounter_accounts e ON e.encounter_account_id=b.encounter_account_id
                        WHERE i.invoice_id=? ORDER BY b.item_id FOR SHARE OF b
                        """,(rs,row) -> new Source(rs.getBigDecimal("line_total"),rs.getBoolean("eligible")),invoiceId);
                var total=sources.stream().map(Source::amount).reduce(new BigDecimal("0.00"),BigDecimal::add);
                if (sources.isEmpty() || sources.stream().anyMatch(source -> !source.eligible())
                        || total.compareTo(header.total())!=0) throw conflict();
                var time=Timestamp.from(now);
                if (jdbc.update("UPDATE billing.invoices SET status='ISSUED',invoice_number=?,issued_at=?,"
                        +"updated_at=?,version=version+1 WHERE invoice_id=? AND status='DRAFT' AND version=?",
                        "BIL-"+invoiceId,time,time,invoiceId,expectedVersion)!=1) throw conflict();
                var invoice=new JdbcInvoiceQueryRepository(jdbc).find(invoiceId).orElseThrow();
                String snapshot=mapper.writeValueAsString(invoice);
                UUID eventId=UUID.randomUUID();
                Map<String,Object> payload=new LinkedHashMap<>(Map.of("invoiceId",invoiceId,"patientId",invoice.patientId(),
                        "total",invoice.total(),"currency","COP","issuedAt",now.toString(),"issuedBy",actor));
                if (invoice.appointmentId()!=null) payload.put("appointmentId",invoice.appointmentId());
                var event=Map.of("eventId",eventId,"eventType","InvoiceIssued","aggregateId",invoiceId,
                        "aggregateType","Invoice","occurredAt",now.toString(),"version",1,"producer","billing-service",
                        "payload",payload,"metadata",Map.of("correlationId",correlationId,"causationId",key,"actorUserId",actor));
                jdbc.update("INSERT INTO billing.outbox_events(event_id,aggregate_id,event_type,event_version,occurred_at,event_body)"
                        +" VALUES (?,?,'InvoiceIssued',1,?,?::jsonb)",eventId,invoiceId,time,mapper.writeValueAsString(event));
                jdbc.update("INSERT INTO billing.invoice_issuances(invoice_id,event_id,issued_by,invoice_version,invoice_snapshot)"
                        +" VALUES (?,?,?,?,?::jsonb)",invoiceId,eventId,actor,invoice.version(),snapshot);
                return new CreatedResource(200,"/api/v1/invoices/"+invoiceId,snapshot);
            });
        } catch (PriceCreationConflict changed) { throw new InvoiceIssuanceConflict(changed.code()); }
    }

    private static InvoiceIssuanceConflict conflict() { return new InvoiceIssuanceConflict("INVOICE_ISSUANCE_CONFLICT"); }
    private record Header(String status,int version,BigDecimal total) { }
    private record Source(BigDecimal amount,boolean eligible) { }
}
