package com.dilucca.billing.infrastructure.messaging;

import com.dilucca.billing.domain.messaging.EventPublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/** Persistent mandatory messages with confirms; routing uses the canonical event name. */
public final class RabbitEventPublisher implements EventPublisher {
    private final Supplier<String> credential;
    private final String exchange;
    private final int timeout;
    private final boolean local;
    public RabbitEventPublisher(Supplier<String> credential,String exchange,Duration timeout,boolean local) {
        if (exchange==null || !exchange.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}") || timeout==null
                || timeout.toMillis()<1 || timeout.compareTo(Duration.ofSeconds(10))>0) {
            throw new IllegalArgumentException("Invalid broker publisher settings");
        }
        this.credential=Objects.requireNonNull(credential); this.exchange=exchange;
        this.timeout=(int)timeout.toMillis(); this.local=local;
    }
    @Override public void publish(String routingKey,UUID eventId,String json) {
        try {
            URI uri=URI.create(credential.get());
            if (uri.getHost()==null || uri.getRawUserInfo()==null || !uri.getRawUserInfo().contains(":")
                    || uri.getQuery()!=null || uri.getFragment()!=null
                    || !("amqps".equals(uri.getScheme()) || local && "amqp".equals(uri.getScheme()))) {
                throw new Unavailable();
            }
            var factory=new ConnectionFactory();
            // Explicit platform trust: never use the legacy trust-all TLS convenience overload.
            if ("amqps".equals(uri.getScheme())) {
                factory.useSslProtocol(SSLContext.getDefault()); factory.enableHostnameVerification();
            }
            factory.setUri(uri); factory.setAutomaticRecoveryEnabled(false);
            factory.setConnectionTimeout(timeout); factory.setHandshakeTimeout(timeout);
            factory.setChannelRpcTimeout(timeout); factory.setShutdownTimeout(timeout);
            try (var connection=factory.newConnection();var channel=connection.createChannel()) {
                channel.exchangeDeclare(exchange,"topic",true);
                channel.confirmSelect();
                var returned=new AtomicBoolean();
                channel.addReturnListener(message -> returned.set(true));
                var properties=new AMQP.BasicProperties.Builder().messageId(eventId.toString())
                        .type(routingKey).contentType("application/json").contentEncoding("UTF-8").deliveryMode(2).build();
                channel.basicPublish(exchange,routingKey,true,properties,json.getBytes(StandardCharsets.UTF_8));
                channel.waitForConfirmsOrDie(timeout);
                if (returned.get()) throw new Unavailable();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new Unavailable();
        } catch (Exception unavailable) { throw new Unavailable(); }
    }
}
