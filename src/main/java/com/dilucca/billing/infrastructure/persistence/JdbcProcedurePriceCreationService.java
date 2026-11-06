package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.PriceCreationConflict;
import com.dilucca.billing.api.pricing.ProcedurePriceCreateService;
import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JdbcProcedurePriceCreationService implements ProcedurePriceCreateService {
    private static final String OPERATION = "BillingCreatepriceversion";
    private static final String FIND = """
            SELECT request_hash, response_status, response_headers->>'Location' AS location,
                   response_body::text AS body FROM billing.idempotency_records
            WHERE actor_id = ? AND operation_name = ? AND resource_key = ? AND idempotency_key = ?
            """;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public JdbcProcedurePriceCreationService(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public CreatedPrice create(UUID key, UUID actor, ProcedurePriceCatalog request) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(request);
        String hash = hash(request);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
        return transaction.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(31704, hashtext(?))",
                    (ResultSetExtractor<Void>) rows -> null,
                    actor + ":" + key + ":" + request.procedureCode());
            CreatedPrice previous = replay(actor, key, request.procedureCode(), hash);
            if (previous != null) return previous;

            UUID id = UUID.randomUUID();
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            try {
                new JdbcProcedurePriceRepository(jdbc).saveNextVersion(id, request, actor, now);
            } catch (IllegalArgumentException conflict) {
                throw new PriceCreationConflict("CONFLICT");
            }
            var price = jdbc.queryForObject("SELECT * FROM billing.procedure_prices WHERE price_id = ?",
                    JdbcProcedurePriceQueryRepository.PRICE_MAPPER, id);
            String location = "/api/v1/procedure-prices/" + id;
            jdbc.update("""
                    INSERT INTO billing.idempotency_records
                    (record_id, actor_id, operation_name, resource_key, idempotency_key, request_hash,
                     response_status, response_headers, response_body, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, 201, ?::jsonb, ?::jsonb, ?, ?)
                    """, UUID.randomUUID(), actor.toString(), OPERATION, request.procedureCode(), key, hash,
                    mapper.writeValueAsString(Map.of("Location", location)), mapper.writeValueAsString(price),
                    Timestamp.from(now), Timestamp.from(now.plus(7, ChronoUnit.DAYS)));
            // Return the stored representation so the initial response and retries have identical bytes.
            return replay(actor, key, request.procedureCode(), hash);
        });
    }

    private CreatedPrice replay(UUID actor, UUID key, String resource, String hash) {
        return jdbc.query(FIND, rows -> {
            if (!rows.next()) return null;
            if (!hash.equals(rows.getString("request_hash"))) {
                throw new PriceCreationConflict("IDEMPOTENCY_CONFLICT");
            }
            return new CreatedPrice(rows.getInt("response_status"), rows.getString("location"),
                    rows.getString("body"));
        }, actor.toString(), OPERATION, resource, key);
    }

    private String hash(ProcedurePriceCatalog request) {
        String canonical = mapper.writeValueAsString(List.of(request.procedureCode(), request.name(),
                request.basePrice().toPlainString(), request.validity().validFrom().toString()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
