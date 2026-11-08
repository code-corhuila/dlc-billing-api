package com.dilucca.billing.api;

import com.dilucca.billing.infrastructure.messaging.RabbitClinicalConsumer;
import com.dilucca.billing.infrastructure.persistence.JdbcClinicalEventHandling;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="BILLING_CLINICAL_CONSUMER_ENABLED",havingValue="true")
@EnableScheduling
public class BillingClinicalConsumerConfiguration {
    private final JdbcClinicalEventHandling worker;
    private final RabbitClinicalConsumer consumer;
    private final int batch;
    public BillingClinicalConsumerConfiguration(JdbcTemplate jdbc,JsonMapper mapper,
            @Value("${BILLING_RABBITMQ_URI_FILE:}") String file,
            @Value("${BILLING_CLINICAL_EXCHANGE:clinical.events}") String exchange,
            @Value("${BILLING_CLINICAL_QUEUE:billing.clinical.v1}") String queue,
            @Value("${BILLING_RABBITMQ_LOCAL_DEVELOPMENT:false}") boolean local,
            @Value("${BILLING_CLINICAL_BATCH_SIZE:10}") int batch) {
        Path path=Path.of(file);
        if(!path.isAbsolute() || batch<1 || batch>100) throw new IllegalArgumentException("Invalid clinical consumer configuration");
        this.batch=batch; this.worker=new JdbcClinicalEventHandling(jdbc,mapper);
        this.consumer=new RabbitClinicalConsumer(() -> {
            try {
                if(Files.size(path)>16384) throw new RabbitClinicalConsumer.Unavailable();
                return Files.readString(path,StandardCharsets.UTF_8).strip();
            } catch(Exception unavailable) { throw new RabbitClinicalConsumer.Unavailable(); }
        },exchange,queue,Duration.ofSeconds(3),local,worker::receive);
    }
    @Scheduled(fixedDelayString="${BILLING_CLINICAL_INTERVAL:PT5S}",initialDelayString="${BILLING_CLINICAL_INTERVAL:PT5S}")
    public void receive() {
        try { worker.sweep(batch); }
        catch(RuntimeException unavailable) { LoggerFactory.getLogger(getClass()).warn("Billing clinical reconciliation pass unavailable"); }
        try { consumer.poll(batch); }
        catch(RuntimeException unavailable) { LoggerFactory.getLogger(getClass()).warn("Billing clinical broker pass unavailable"); }
    }
}
