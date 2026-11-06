package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.items.BillableItem;
import com.dilucca.billing.api.items.BillableItemPage;
import com.dilucca.billing.api.pricing.PaginatedMeta;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBillableItemQueryRepository {
    private final JdbcTemplate jdbc;
    static final org.springframework.jdbc.core.RowMapper<BillableItem> MAPPER = (rs,row) ->
            new BillableItem(rs.getObject("item_id", UUID.class), rs.getObject("patient_id", UUID.class),
                    rs.getObject("procedure_id", UUID.class), rs.getString("description"),
                    rs.getBigDecimal("quantity").toPlainString(), rs.getBigDecimal("unit_price").setScale(2).toPlainString(),
                    rs.getString("currency"), rs.getBoolean("approval_required"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                    rs.getInt("version"), rs.getString("status"));

    public JdbcBillableItemQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public BillableItemPage list(int page, int limit, UUID patientId, UUID procedureId) {
        if (page < 1 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
        int offset = Math.multiplyExact(page-1, limit);
        String where = " WHERE TRUE";
        var parameters = new ArrayList<Object>();
        if (patientId != null) { where += " AND patient_id = ?"; parameters.add(patientId); }
        if (procedureId != null) { where += " AND procedure_id = ?"; parameters.add(procedureId); }
        long total = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM billing.billable_items"+where, Long.class, parameters.toArray()));
        parameters.add(limit);
        parameters.add(offset);
        var items = jdbc.query("""
                SELECT item_id, patient_id, procedure_id, description, quantity, unit_price,
                       currency, approval_required, created_at, updated_at, version, status
                FROM billing.billable_items
                """+where+" ORDER BY created_at ASC, item_id ASC LIMIT ? OFFSET ?", MAPPER, parameters.toArray());
        return new BillableItemPage(items, new PaginatedMeta(page, limit, total,
                Math.toIntExact(total/limit + (total%limit == 0 ? 0 : 1))));
    }
}
