package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.api.pricing.PriceCreationConflict;
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
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

final class JdbcIdempotentCreation {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    JdbcIdempotentCreation(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    CreatedResource execute(UUID key, UUID actor, String operation, String resource,
            List<?> canonicalRequest, Function<Instant, CreatedResource> write) {
        return execute(key,Objects.requireNonNull(actor).toString(),operation,resource,canonicalRequest,write);
    }

    CreatedResource execute(UUID key, String actor, String operation, String resource,
            List<?> canonicalRequest, Function<Instant, CreatedResource> write) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(actor);
        String hash = hash(mapper.writeValueAsString(canonicalRequest));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
        return transaction.execute(status -> {
            // Preserve the existing price lock scope; stored uniqueness also includes the operation.
            jdbc.query("SELECT pg_advisory_xact_lock(31704, hashtext(?))",
                    (ResultSetExtractor<Void>) rows -> null, actor + ":" + key + ":" + resource);
            CreatedResource previous = replay(actor, key, operation, resource, hash);
            if (previous != null) return previous;
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            CreatedResource result = write.apply(now);
            jdbc.update("""
                    INSERT INTO billing.idempotency_records
                    (record_id, actor_id, operation_name, resource_key, idempotency_key, request_hash,
                     response_status, response_headers, response_body, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                    """, UUID.randomUUID(), actor.toString(), operation, resource, key, hash, result.status(),
                    mapper.writeValueAsString(Map.of("Location", result.location())), result.body(),
                    Timestamp.from(now), Timestamp.from(now.plus(7, ChronoUnit.DAYS)));
            // Initial calls and retries return the representation persisted by PostgreSQL.
            return replay(actor, key, operation, resource, hash);
        });
    }

    private CreatedResource replay(String actor, UUID key, String operation, String resource, String hash) {
        return jdbc.query("""
                SELECT request_hash, response_status, response_headers->>'Location' AS location,
                       response_body::text AS body FROM billing.idempotency_records
                WHERE actor_id = ? AND operation_name = ? AND resource_key = ? AND idempotency_key = ?
                """, rows -> {
            if (!rows.next()) return null;
            if (!hash.equals(rows.getString("request_hash"))) {
                throw new PriceCreationConflict("IDEMPOTENCY_CONFLICT");
            }
            return new CreatedResource(rows.getInt("response_status"), rows.getString("location"),
                    rows.getString("body"));
        }, actor.toString(), operation, resource, key);
    }

    private String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
