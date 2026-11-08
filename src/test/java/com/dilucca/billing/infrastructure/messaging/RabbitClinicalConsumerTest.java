package com.dilucca.billing.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.infrastructure.persistence.JdbcClinicalEventHandling;
import com.rabbitmq.client.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_RABBITMQ_URI",matches=".+")
class RabbitClinicalConsumerTest {
    final String exchange="clinical-test-"+UUID.randomUUID(),queue="billing-clinical-"+UUID.randomUUID();
    Connection connection;
    Channel channel;
    String valid;
    @BeforeEach void setup() throws Exception {
        var factory=new ConnectionFactory(); factory.setUri(System.getenv("BILLING_TEST_RABBITMQ_URI"));
        factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(2000);
        connection=factory.newConnection(); channel=connection.createChannel(); channel.confirmSelect();
        try(var stream=getClass().getResourceAsStream("/clinical/procedure.json")) { valid=new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
    }
    @AfterEach void clean() throws Exception {
        if(channel!=null && channel.isOpen()) {
            channel.queueDelete(queue); channel.queueDelete(queue+".quarantine"); channel.exchangeDelete(exchange);
            channel.close();
        }
        if(connection!=null) connection.close();
    }
    RabbitClinicalConsumer consumer(Consumer<String> handler) {
        return new RabbitClinicalConsumer(() -> System.getenv("BILLING_TEST_RABBITMQ_URI"),exchange,queue,Duration.ofSeconds(2),true,handler);
    }
    AMQP.BasicProperties properties() {
        return new AMQP.BasicProperties.Builder().messageId("81000000-0000-4000-8000-000000000001")
                .type("ProcedureCompleted").appId("clinical-service").contentType("application/json").deliveryMode(2).build();
    }
    void publish(byte[] body,AMQP.BasicProperties properties) throws Exception {
        channel.basicPublish(exchange,"ProcedureCompleted",true,properties,body); channel.waitForConfirmsOrDie(2000);
    }
    @Test void commitsHandlerBeforeAckAndRetriesWithIdenticalBodyAndIdentity() throws Exception {
        var calls=new AtomicInteger();
        var consumer=consumer(body -> { assertEquals(valid,body); if(calls.incrementAndGet()==1) throw new IllegalStateException("temporary DB failure"); });
        assertEquals(0,consumer.poll(10)); publish(valid.getBytes(StandardCharsets.UTF_8),properties());
        assertEquals(1,consumer.poll(10)); assertEquals(1,channel.messageCount(queue));
        var retry=channel.basicGet(queue,false); assertArrayEquals(valid.getBytes(StandardCharsets.UTF_8),retry.getBody());
        assertEquals(properties().getMessageId(),retry.getProps().getMessageId());
        assertEquals(1,((Number)retry.getProps().getHeaders().get("x-billing-retry")).intValue());
        channel.basicNack(retry.getEnvelope().getDeliveryTag(),false,true);
        assertEquals(1,consumer.poll(10)); assertEquals(2,calls.get()); assertEquals(0,channel.messageCount(queue));
    }
    @Test void quarantinesMalformedUtf8JsonAndMismatchedMessageIdentityWithoutCallingHandler() throws Exception {
        var calls=new AtomicInteger(); var consumer=consumer(body -> calls.incrementAndGet()); consumer.poll(1);
        publish("invalid JSON".getBytes(StandardCharsets.UTF_8),properties());
        publish(new byte[]{(byte)0xc3},properties());
        publish(valid.getBytes(StandardCharsets.UTF_8),properties().builder().messageId(UUID.randomUUID().toString()).build());
        assertEquals(3,consumer.poll(10)); assertEquals(0,calls.get()); assertEquals(3,channel.messageCount(queue+".quarantine"));
    }
    @Test void retainsOriginalWhenQuarantinePublishIsReturnedThenRecoversWithoutLoss() throws Exception {
        var consumer=consumer(body -> {
            try { channel.queueDelete(queue+".quarantine"); } catch(Exception failure) { throw new IllegalStateException(); }
            throw new JdbcClinicalEventHandling.Permanent();
        }); consumer.poll(1); publish(valid.getBytes(StandardCharsets.UTF_8),properties());
        assertThrows(RabbitClinicalConsumer.Unavailable.class,() -> consumer.poll(1));
        assertEquals(1,channel.messageCount(queue));
        consumer(body -> { throw new JdbcClinicalEventHandling.Permanent(); }).poll(1);
        var rejected=channel.basicGet(queue+".quarantine",true);
        assertNotNull(rejected); assertArrayEquals(valid.getBytes(StandardCharsets.UTF_8),rejected.getBody());
        assertEquals(0,channel.messageCount(queue));
    }
    @Test void exhaustsBoundedRetriesIntoDurableQuarantine() throws Exception {
        var calls=new AtomicInteger(); var consumer=consumer(body -> { calls.incrementAndGet(); throw new IllegalStateException(); });
        consumer.poll(1); publish(valid.getBytes(StandardCharsets.UTF_8),properties());
        for(int attempt=0;attempt<10;attempt++) assertEquals(1,consumer.poll(10));
        assertEquals(10,calls.get()); assertEquals(0,channel.messageCount(queue));
        var rejected=channel.basicGet(queue+".quarantine",true); assertNotNull(rejected);
        assertEquals("RETRY_EXHAUSTED",rejected.getProps().getHeaders().get("x-billing-failure").toString());
        assertEquals(2,rejected.getProps().getDeliveryMode());
    }
}
