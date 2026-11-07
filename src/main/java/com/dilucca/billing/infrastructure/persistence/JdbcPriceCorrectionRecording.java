package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.items.PriceCorrectionInput;
import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.api.pricing.PriceCreationConflict;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Correction, protected audit and exact replay commit in one owner transaction. */
@Service
public class JdbcPriceCorrectionRecording {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public JdbcPriceCorrectionRecording(JdbcTemplate jdbc,JsonMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public CreatedResource record(UUID key,UUID actor,UUID item,PriceCorrectionInput input) {
        Objects.requireNonNull(item); Objects.requireNonNull(input);
        try {
            return new JdbcIdempotentCreation(jdbc,mapper).execute(key,actor,"BillingApprovemanualprice",
                    "/api/v1/billable-items/"+item+"/approvals",
                    List.of(input.unitPrice().toPlainString(),input.reason(),input.expectedVersion()),now->{
                jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
                    try(var statement=connection.prepareStatement("SELECT billing.correct_price(?,?,?,?,?)")) {
                        statement.setObject(1,item); statement.setObject(2,actor); statement.setBigDecimal(3,input.unitPrice());
                        statement.setString(4,input.reason()); statement.setInt(5,input.expectedVersion()); statement.execute();
                    }
                    return null;
                });
                var updated=jdbc.query("SELECT * FROM billing.billable_items WHERE item_id=?",JdbcBillableItemQueryRepository.MAPPER,item).getFirst();
                return new CreatedResource(200,"/api/v1/billable-items/"+item,mapper.writeValueAsString(updated));
            });
        } catch(PriceCreationConflict conflict) { throw new Conflict(conflict.code()); }
        catch(DataAccessException failure) {
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SQLException sql) {
                    if("P0002".equals(sql.getSQLState())) throw new Missing();
                    if("40001".equals(sql.getSQLState())) throw new Conflict("CORRECTION_CONFLICT");
                }
            }
            throw failure;
        }
    }
    public static class Missing extends RuntimeException { }
    public static class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code) { super("Correction conflicts with current financial state"); this.code=code; }
        public String code() { return code; }
    }
}
