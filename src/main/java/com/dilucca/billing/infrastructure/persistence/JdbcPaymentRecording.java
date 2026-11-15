package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.api.pricing.PriceCreationConflict;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Invoice lock, immutable payment, event snapshots and replay share one local transaction. */
@org.springframework.stereotype.Service
public class JdbcPaymentRecording {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public JdbcPaymentRecording(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public CreatedResource record(UUID key,UUID actor,UUID invoice,BigDecimal amount,String method,long version,UUID trace) {
        if (amount==null || amount.signum()<=0 || amount.scale()>2 || amount.precision()-amount.scale()>12
                || version<1 || method==null || method.isBlank() || method.length()>40) throw new IllegalArgumentException();
        String exact=amount.setScale(2).toPlainString();
        try {
            return new JdbcIdempotentCreation(jdbc,mapper).execute(key,actor,"BillingRecordpayment",
                    "/api/v1/invoices/"+invoice+"/payments",List.of(exact,method,version),now -> {
                var headers=jdbc.query("SELECT patient_id,status,version,balance,total FROM billing.invoices WHERE invoice_id=? FOR UPDATE",
                        (rs,row) -> new Header(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),rs.getBigDecimal(4),rs.getBigDecimal(5)),invoice);
                if (headers.isEmpty()) throw new Missing();
                var header=headers.getFirst();
                if (header.version()!=version) throw new Conflict("STALE_VERSION");
                if (!Set.of("ISSUED","PARTIALLY_PAID").contains(header.status())) throw new Conflict("INVOICE_NOT_PAYABLE");
                if (amount.compareTo(header.balance())>0) throw new Conflict("PAYMENT_EXCEEDS_BALANCE");
                if (amount.compareTo(com.dilucca.billing.domain.InitialPaymentMinimum.forBalance(header.total(),header.balance()))<0)
                    throw new Conflict("INITIAL_PAYMENT_BELOW_MINIMUM");
                if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM billing.payment_methods WHERE method=? AND active)",Boolean.class,method)))
                    throw new IllegalArgumentException();
                UUID id=UUID.randomUUID();
                // Database trigger also enforces payable state and derives balance/status/version under its lock.
                jdbc.update("INSERT INTO billing.payments(payment_id,invoice_id,amount,method,recorded_by,recorded_at,created_at) VALUES (?,?,?,?,?,?,?)",
                        id,invoice,amount,method,actor,Timestamp.from(now),Timestamp.from(now));
                var current=new JdbcInvoiceQueryRepository(jdbc).find(invoice).orElseThrow();
                var payment=Map.of("id",id,"invoiceId",invoice,"amount",exact,"method",method,
                        "recordedBy",actor,"recordedAt",now,"createdAt",now,"updatedAt",now,"version",1);
                Map<String,Object> payload=new LinkedHashMap<>(Map.of("paymentId",id,"invoiceId",invoice,"patientId",header.patient(),
                        "amount",exact,"currency","COP","remainingBalance",current.balance(),"method",method,
                        "recordedAt",now.toString(),"recordedBy",actor));
                UUID event=event("PaymentRecorded",invoice,key,actor,trace,now,payload);
                jdbc.update("INSERT INTO billing.payment_receipts(payment_id,event_id,invoice_id,invoice_version,invoice_snapshot) VALUES (?,?,?,?,?::jsonb)",
                        id,event,invoice,current.version(),mapper.writeValueAsString(current));
                if (current.status().equals("PAID")) event("InvoicePaid",invoice,key,actor,trace,now,
                        Map.of("invoiceId",invoice,"patientId",header.patient(),"paidAt",now.toString()));
                return new CreatedResource(201,"/api/v1/invoices/"+invoice+"/payments#"+id,mapper.writeValueAsString(payment));
            });
        } catch(PriceCreationConflict changed) { throw new Conflict(changed.code()); }
    }
    private UUID event(String type,UUID invoice,UUID key,UUID actor,UUID trace,Instant now,Map<String,Object> payload) {
        UUID id=UUID.randomUUID();
        var body=Map.of("eventId",id,"eventType",type,"aggregateId",invoice,"aggregateType","Invoice",
                "occurredAt",now.toString(),"version",1,"producer","billing-service","payload",payload,
                "metadata",Map.of("correlationId",trace,"causationId",key,"actorUserId",actor));
        jdbc.update("INSERT INTO billing.outbox_events(event_id,aggregate_id,event_type,event_version,occurred_at,event_body) VALUES (?,?,?,1,?,?::jsonb)",
                id,invoice,type,Timestamp.from(now),mapper.writeValueAsString(body));
        return id;
    }
    private record Header(UUID patient,String status,int version,BigDecimal balance,BigDecimal total) { }
    public static class Missing extends RuntimeException { }
    public static class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code) { super("Payment conflicts with current financial state"); this.code=code; }
        public String code() { return code; }
    }
}
