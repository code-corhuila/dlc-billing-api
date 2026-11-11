package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.api.pricing.PriceCreationConflict;
import com.dilucca.billing.domain.invoicing.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Atomic allocation after HTTP authentication and authoritative owner validation. */
public final class JdbcInvoiceDraftCreation {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final FinalizedAppointments appointments;

    public JdbcInvoiceDraftCreation(JdbcTemplate jdbc,JsonMapper mapper,FinalizedAppointments appointments) {
        this.jdbc=Objects.requireNonNull(jdbc); this.mapper=Objects.requireNonNull(mapper);
        this.appointments=Objects.requireNonNull(appointments);
    }

    public CreatedResource create(UUID key,UUID actor,InvoiceDraft request) {
        try {
            return new JdbcIdempotentCreation(jdbc,mapper).execute(key,actor,"BillingCreatedraftinvoice",
                    "/api/v1/invoices",List.of(request.patientId().toString(),
                    request.appointmentId()==null ? "" : request.appointmentId().toString(),request.itemIds()),now -> {
                String placeholders=String.join(",",Collections.nCopies(request.itemIds().size(),"?"));
                var sources=jdbc.query("""
                        SELECT b.item_id,b.patient_id,b.appointment_id,b.status,b.line_total,
                               EXISTS(SELECT 1 FROM billing.invoice_items i WHERE i.item_id=b.item_id) AS allocated,
                               e.status='CHARGES_COMPLETED' AS completed
                        FROM billing.billable_items b
                        JOIN billing.encounter_accounts e ON e.encounter_account_id=b.encounter_account_id
                        WHERE b.item_id IN (
                        """+placeholders+") ORDER BY b.item_id FOR UPDATE OF b",(rs,row) -> new Source(
                        rs.getObject("item_id",UUID.class),rs.getObject("patient_id",UUID.class),
                        rs.getObject("appointment_id",UUID.class),rs.getString("status"),
                        rs.getBigDecimal("line_total"),rs.getBoolean("allocated"),rs.getBoolean("completed")),
                        request.itemIds().toArray());
                if (sources.size()!=request.itemIds().size() || sources.stream().anyMatch(source ->
                        !source.patient().equals(request.patientId()) || !source.status().equals("UNBILLED")
                        || source.allocated() || request.appointmentId()!=null
                        && !source.appointment().equals(request.appointmentId()))) throw conflict();
                if (sources.stream().anyMatch(source -> !source.completed()))
                    throw new InvoiceDraftConflict("ENCOUNTER_PRICING_PENDING");
                BigDecimal total=sources.stream().map(Source::amount).reduce(new BigDecimal("0.00"),BigDecimal::add);
                if (total.compareTo(new BigDecimal("999999999999.99"))>0) throw conflict();
                Map<UUID,Long> validations=new LinkedHashMap<>();
                for (Source source:sources) validations.computeIfAbsent(source.appointment(),
                        id -> appointments.requireFinalized(id,request.patientId()));
                UUID appointment=validations.size()==1 ? validations.keySet().iterator().next() : null;
                UUID id=UUID.randomUUID();
                Timestamp timestamp=Timestamp.from(now);
                jdbc.update("""
                        INSERT INTO billing.invoices(invoice_id,patient_id,appointment_id,total,balance,
                        created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)
                        """,id,request.patientId(),appointment,total,total,actor,timestamp,timestamp);
                for (var validation:validations.entrySet()) jdbc.update("""
                        INSERT INTO billing.invoice_appointment_validations
                        (invoice_id,appointment_id,patient_id,appointment_version,validated_at) VALUES (?,?,?,?,?)
                        """,id,validation.getKey(),request.patientId(),validation.getValue(),timestamp);
                for (Source source:sources) {
                    jdbc.update("INSERT INTO billing.invoice_items(invoice_id,patient_id,item_id) VALUES (?,?,?)",
                            id,request.patientId(),source.id());
                    if (jdbc.update("UPDATE billing.billable_items SET status='BILLED',version=version+1,updated_at=?"
                            +" WHERE item_id=? AND status='UNBILLED'",timestamp,source.id())!=1) throw conflict();
                }
                var invoice=new JdbcInvoiceQueryRepository(jdbc).find(id).orElseThrow();
                return new CreatedResource(201,"/api/v1/invoices/"+id,mapper.writeValueAsString(invoice));
            });
        } catch (PriceCreationConflict replay) { throw new InvoiceDraftConflict(replay.code()); }
    }

    private InvoiceDraftConflict conflict() { return new InvoiceDraftConflict("INVOICE_SOURCE_CONFLICT"); }
    // Only charges of an encounter whose declared charges are complete can be invoiced (ADR-015); rejecting them
    // here keeps them UNBILLED instead of locking them in a draft that could never be issued.
    private record Source(UUID id,UUID patient,UUID appointment,String status,BigDecimal amount,boolean allocated,
            boolean completed) { }
}
