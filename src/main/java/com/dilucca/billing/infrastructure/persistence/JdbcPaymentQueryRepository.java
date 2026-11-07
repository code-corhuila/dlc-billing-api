package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.PaginatedMeta;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.stereotype.Repository
public class JdbcPaymentQueryRepository {
    private final JdbcTemplate jdbc;
    public JdbcPaymentQueryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Map<String,Object> list(UUID invoice,int page,int limit) {
        if(page<1 || limit<1 || limit>100) throw new IllegalArgumentException();
        int offset=Math.multiplyExact(page-1,limit);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM billing.invoices WHERE invoice_id=?)",Boolean.class,invoice)))
            throw new JdbcPaymentRecording.Missing();
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM billing.payments WHERE invoice_id=?",Long.class,invoice));
        var rows=jdbc.query("SELECT payment_id,amount,method,recorded_by,recorded_at,created_at,version FROM billing.payments "
                +"WHERE invoice_id=? ORDER BY created_at,payment_id LIMIT ? OFFSET ?",(rs,row) -> Map.of(
                        "id",rs.getObject(1,UUID.class),"invoiceId",invoice,"amount",rs.getBigDecimal(2).setScale(2).toPlainString(),
                        "method",rs.getString(3),"recordedBy",rs.getObject(4,UUID.class),"recordedAt",rs.getTimestamp(5).toInstant(),
                        "createdAt",rs.getTimestamp(6).toInstant(),"updatedAt",rs.getTimestamp(6).toInstant(),"version",rs.getInt(7)),invoice,limit,offset);
        return Map.of("data",rows,"meta",new PaginatedMeta(page,limit,total,Math.toIntExact(total/limit+(total%limit==0 ? 0 : 1))));
    }
}
