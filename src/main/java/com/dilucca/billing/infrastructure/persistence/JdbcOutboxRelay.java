package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.domain.messaging.EventPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Publication follows financial commit. A failed confirmation never marks an event delivered. */
public final class JdbcOutboxRelay {
    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;
    public JdbcOutboxRelay(JdbcTemplate jdbc,EventPublisher publisher) {
        this.jdbc=Objects.requireNonNull(jdbc); this.publisher=Objects.requireNonNull(publisher);
    }
    public int relay(Instant now,int limit) {
        Objects.requireNonNull(now);
        if (limit<1 || limit>100) throw new IllegalArgumentException("Invalid relay batch size");
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        return transaction.execute(status -> {
            var due=jdbc.query("""
                    SELECT d.event_id,o.event_type,o.event_body::text,d.attempts
                    FROM billing.outbox_deliveries d JOIN billing.outbox_events o ON o.event_id=d.event_id
                    WHERE d.status='PENDING' AND (d.next_attempt_at IS NULL OR d.next_attempt_at<=?)
                    ORDER BY o.created_at,d.event_id LIMIT ? FOR UPDATE OF d SKIP LOCKED
                    """,(rs,row) -> new Pending(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4)),
                    Timestamp.from(now),limit);
            int sent=0;
            for (var row:due) {
                try { publisher.publish(row.type(),row.id(),row.body()); }
                catch (EventPublisher.Unavailable unavailable) {
                    int attempts=row.attempts()+1;
                    long delay=Math.min(5000L << (attempts-1),900000L)+ThreadLocalRandom.current().nextLong(1000);
                    jdbc.update("""
                            UPDATE billing.outbox_deliveries SET status=?,attempts=?,next_attempt_at=?,
                            last_error_code='BROKER_UNAVAILABLE' WHERE event_id=?
                            """,attempts==10 ? "FAILED" : "PENDING",attempts,
                            attempts==10 ? null : Timestamp.from(now.plusMillis(delay)),row.id());
                    break; // Keep the remaining batch untouched while transport is unavailable.
                }
                jdbc.update("""
                        UPDATE billing.outbox_deliveries SET status='PUBLISHED',attempts=attempts+1,
                        published_at=?,next_attempt_at=NULL,last_error_code=NULL WHERE event_id=?
                        """,Timestamp.from(now),row.id());
                sent++;
            }
            return sent;
        });
    }
    private record Pending(UUID id,String type,String body,int attempts) { }
}
