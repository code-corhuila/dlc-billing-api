package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.invoices.Invoice;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@org.springframework.stereotype.Repository
public class JdbcInvoiceEventQuery {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public JdbcInvoiceEventQuery(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public Optional<State> find(UUID invoice,UUID event) {
        return jdbc.query("""
                SELECT invoice_snapshot::text AS snapshot,NULL::text AS amount FROM billing.invoice_issuances
                WHERE invoice_id=? AND event_id=?
                UNION ALL
                SELECT r.invoice_snapshot::text,e.event_body->'payload'->>'amount'
                FROM billing.payment_receipts r JOIN billing.outbox_events e ON e.event_id=r.event_id
                WHERE r.invoice_id=? AND r.event_id=? AND e.event_type='PaymentRecorded'
                """,(rs,row) -> new State(mapper.readValue(rs.getString(1),Invoice.class),rs.getString(2)),invoice,event,invoice,event)
                .stream().findFirst();
    }
    public record State(Invoice invoice,String recentPaymentAmount) { }
}
