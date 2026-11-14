package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.invoices.Invoice;
import com.dilucca.billing.api.invoices.InvoicePage;
import com.dilucca.billing.api.items.BillableItem;
import com.dilucca.billing.api.pricing.PaginatedMeta;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvoiceQueryRepository {
    private final JdbcTemplate jdbc;
    private static final String HEADER="SELECT invoice_id,patient_id,appointment_id,invoice_number,issued_at,"
            +"total,balance,currency,status,created_at,updated_at,version FROM billing.invoices";
    private static final RowMapper<Invoice> MAPPER=(rs,row) -> new Invoice(
            rs.getObject("invoice_id",UUID.class),rs.getObject("patient_id",UUID.class),
            rs.getObject("appointment_id",UUID.class),rs.getString("invoice_number"),
            rs.getTimestamp("issued_at")==null ? null : rs.getTimestamp("issued_at").toInstant(),List.of(),
            rs.getBigDecimal("total").toPlainString(),rs.getBigDecimal("balance").toPlainString(),
            rs.getString("currency"),rs.getString("status"),rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),rs.getInt("version"));

    public JdbcInvoiceQueryRepository(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    public InvoicePage list(int page,int limit,UUID patient) {
        if (page < 1 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
        int offset=Math.multiplyExact(page-1,limit);
        String where=patient==null ? "" : " WHERE patient_id=?";
        Object[] filter=patient==null ? new Object[]{} : new Object[]{patient};
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM billing.invoices"+where,Long.class,filter));
        var parameters=new ArrayList<>(Arrays.asList(filter));
        parameters.add(limit); parameters.add(offset);
        var rows=jdbc.query(HEADER+where+" ORDER BY created_at ASC,invoice_id ASC LIMIT ? OFFSET ?",MAPPER,parameters.toArray());
        return new InvoicePage(items(rows),new PaginatedMeta(page,limit,total,
                Math.toIntExact(total/limit+(total%limit==0 ? 0 : 1))));
    }

    public Optional<Invoice> find(UUID id) {
        return items(jdbc.query(HEADER+" WHERE invoice_id=?",MAPPER,id)).stream().findFirst();
    }

    private List<Invoice> items(List<Invoice> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID,List<BillableItem>> grouped=new HashMap<>();
        String placeholders=String.join(",",Collections.nCopies(rows.size(),"?"));
        jdbc.query("""
                SELECT links.invoice_id,b.item_id,b.patient_id,b.procedure_id,b.description,b.quantity,b.unit_price,
                       b.currency,b.approval_required,b.created_at,b.updated_at,b.version,b.status
                FROM billing.invoice_items links JOIN billing.billable_items b ON b.item_id=links.item_id
                WHERE links.invoice_id IN (
                """+placeholders+") ORDER BY b.created_at ASC,b.item_id ASC", rs -> {
                    grouped.computeIfAbsent(rs.getObject("invoice_id",UUID.class),key -> new ArrayList<>())
                            .add(JdbcBillableItemQueryRepository.MAPPER.mapRow(rs,0));
                },rows.stream().map(Invoice::id).toArray());
        return rows.stream().map(row -> row.withItems(grouped.getOrDefault(row.id(),List.of()))).toList();
    }
}
