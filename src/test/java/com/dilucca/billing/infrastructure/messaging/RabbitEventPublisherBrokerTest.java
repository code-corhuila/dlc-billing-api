package com.dilucca.billing.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.domain.messaging.EventPublisher;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RABBITMQ_URI",matches=".+")
class RabbitEventPublisherBrokerTest {
    @Test void publishesPersistentExactMessageToDurableQueueWithConfirmedIdentity() throws Exception {
        String exchange="billing-test-"+UUID.randomUUID(),queue=exchange+"-consumer";
        var factory=new ConnectionFactory(); factory.setUri(System.getenv("BILLING_TEST_RABBITMQ_URI"));
        try (var connection=factory.newConnection();var channel=connection.createChannel()) {
            channel.exchangeDeclare(exchange,"topic",true);
            channel.queueDeclare(queue,true,false,false,null); channel.queueBind(queue,exchange,"InvoiceIssued");
            try {
                UUID id=UUID.randomUUID(); String body="{\"eventId\":\""+id+"\"}";
                var credential=new java.util.concurrent.atomic.AtomicReference<>(System.getenv("BILLING_TEST_RABBITMQ_URI"));
                var publisher=new RabbitEventPublisher(credential::get,exchange,Duration.ofSeconds(3),true);
                credential.set("invalid-rotated-credential");
                assertThrows(EventPublisher.Unavailable.class,() -> publisher.publish("InvoiceIssued",id,body));
                credential.set(System.getenv("BILLING_TEST_RABBITMQ_URI"));
                publisher.publish("InvoiceIssued",id,body);
                var received=channel.basicGet(queue,false);
                assertNotNull(received); assertEquals(body,new String(received.getBody(),StandardCharsets.UTF_8));
                assertEquals(id.toString(),received.getProps().getMessageId());
                assertEquals("InvoiceIssued",received.getProps().getType());
                assertEquals("application/json",received.getProps().getContentType());
                assertEquals(2,received.getProps().getDeliveryMode());
                channel.basicAck(received.getEnvelope().getDeliveryTag(),false);
            } finally { channel.queueDelete(queue); channel.exchangeDelete(exchange); }
        }
    }
    @Test void mandatoryUnroutableMessageIsNotTreatedAsPublished() throws Exception {
        String exchange="billing-test-unrouted-"+UUID.randomUUID();
        var factory=new ConnectionFactory(); factory.setUri(System.getenv("BILLING_TEST_RABBITMQ_URI"));
        try (var connection=factory.newConnection();var channel=connection.createChannel()) {
            try {
                var publisher=new RabbitEventPublisher(() -> System.getenv("BILLING_TEST_RABBITMQ_URI"),exchange,Duration.ofSeconds(3),true);
                assertThrows(EventPublisher.Unavailable.class,() -> publisher.publish("InvoiceIssued",UUID.randomUUID(),"{}"));
            } finally { channel.exchangeDelete(exchange); }
        }
    }
}
