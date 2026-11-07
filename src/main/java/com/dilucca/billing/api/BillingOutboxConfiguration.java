package com.dilucca.billing.api;

import com.dilucca.billing.domain.messaging.EventPublisher;
import com.dilucca.billing.infrastructure.messaging.RabbitEventPublisher;
import com.dilucca.billing.infrastructure.persistence.JdbcOutboxRelay;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="BILLING_OUTBOX_RELAY_ENABLED",havingValue="true")
@EnableScheduling
public class BillingOutboxConfiguration {
    private final JdbcOutboxRelay relay;
    private final int batch;
    public BillingOutboxConfiguration(JdbcTemplate jdbc,
            @Value("${BILLING_RABBITMQ_URI_FILE:}") String file,
            @Value("${BILLING_RABBITMQ_EXCHANGE:billing.events}") String exchange,
            @Value("${BILLING_RABBITMQ_LOCAL_DEVELOPMENT:false}") boolean local,
            @Value("${BILLING_OUTBOX_BATCH_SIZE:10}") int batch) {
        Path path=Path.of(file);
        if (!path.isAbsolute() || batch<1 || batch>100) throw new IllegalArgumentException("Invalid outbox configuration");
        this.batch=batch;
        this.relay=new JdbcOutboxRelay(jdbc,new RabbitEventPublisher(() -> {
            try {
                if (Files.size(path)>16384) throw new EventPublisher.Unavailable();
                return Files.readString(path,StandardCharsets.UTF_8).strip();
            } catch (Exception unavailable) { throw new EventPublisher.Unavailable(); }
        },exchange,Duration.ofSeconds(3),local));
    }
    @Scheduled(fixedDelayString="${BILLING_OUTBOX_INTERVAL:PT5S}",initialDelayString="${BILLING_OUTBOX_INTERVAL:PT5S}")
    public void publish() {
        try { relay.relay(Instant.now(),batch); }
        catch (RuntimeException failed) { LoggerFactory.getLogger(getClass()).warn("Billing outbox pass failed"); }
    }
}
