package com.dilucca.billing.infrastructure.messaging;

import com.dilucca.billing.infrastructure.persistence.JdbcClinicalEventHandling;
import com.rabbitmq.client.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/** Bounded owner pull; source ACK follows DB commit or confirmed durable transfer. */
public final class RabbitClinicalConsumer {
    private static final Set<String> TYPES=Set.of("ProcedureCompleted","ConsultationRecorded","ClinicalCareCompleted");
    private final Supplier<String> credential;
    private final String exchange,queue;
    private final int timeout;
    private final boolean local;
    private final Consumer<String> handler;
    public RabbitClinicalConsumer(Supplier<String> credential,String exchange,String queue,Duration timeout,boolean local,Consumer<String> handler) {
        if(exchange==null || !exchange.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
                || queue==null || !queue.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || timeout==null
                || timeout.toMillis()<1 || timeout.compareTo(Duration.ofSeconds(10))>0) throw new IllegalArgumentException();
        this.credential=Objects.requireNonNull(credential); this.exchange=exchange; this.queue=queue;
        this.timeout=(int)timeout.toMillis(); this.local=local; this.handler=Objects.requireNonNull(handler);
    }
    public int poll(int batch) {
        if(batch<1 || batch>100) throw new IllegalArgumentException();
        try(var connection=factory().newConnection();var channel=connection.createChannel()) {
            channel.exchangeDeclare(exchange,"topic",true);
            // Explicit classic durable queues: application-confirmed retries do not depend on broker delivery counters.
            channel.queueDeclare(queue,true,false,false,Map.of("x-queue-type","classic"));
            channel.queueDeclare(queue+".quarantine",true,false,false,Map.of("x-queue-type","classic"));
            for(String type:TYPES) channel.queueBind(queue,exchange,type);
            channel.confirmSelect(); var returned=new AtomicBoolean(); channel.addReturnListener(message -> returned.set(true));
            int handled=0;
            for(int i=0;i<batch;i++) {
                var delivery=channel.basicGet(queue,false); if(delivery==null) break;
                int retry=retry(delivery.getProps());
                String body;
                try { body=body(delivery); }
                catch(JdbcClinicalEventHandling.Permanent invalid) {
                    transfer(channel,returned,delivery,queue+".quarantine",retry,"INVALID_SOURCE");
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false); handled++; continue;
                }
                try { handler.accept(body); }
                catch(JdbcClinicalEventHandling.Permanent invalid) {
                    transfer(channel,returned,delivery,queue+".quarantine",retry,"INVALID_SOURCE");
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false); handled++; continue;
                } catch(RuntimeException unavailable) {
                    int attempt=retry+1;
                    transfer(channel,returned,delivery,attempt>=10 ? queue+".quarantine" : queue,attempt,attempt>=10 ? "RETRY_EXHAUSTED" : null);
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false); handled++; break;
                }
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false); handled++;
            }
            return handled;
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Unavailable(); }
        catch(Exception unavailable) { throw new Unavailable(); }
    }
    private String body(GetResponse delivery) {
        try {
            if(delivery.getBody().length>262144) throw new JdbcClinicalEventHandling.Permanent();
            String body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(delivery.getBody())).toString();
            var fact=new ClinicalEventDecoder().decode(body); var properties=delivery.getProps(); var route=delivery.getEnvelope();
            boolean routed=exchange.equals(route.getExchange()) && fact.eventType().equals(route.getRoutingKey())
                    || "".equals(route.getExchange()) && queue.equals(route.getRoutingKey());
            if(!routed || !fact.eventId().toString().equalsIgnoreCase(properties.getMessageId())
                    || !fact.eventType().equals(properties.getType()) || !"application/json".equals(properties.getContentType())
                    || properties.getAppId()!=null && !"clinical-service".equals(properties.getAppId())
                    || properties.getContentEncoding()!=null && !"UTF-8".equalsIgnoreCase(properties.getContentEncoding()))
                throw new JdbcClinicalEventHandling.Permanent();
            return body;
        } catch(Exception invalid) { throw new JdbcClinicalEventHandling.Permanent(); }
    }
    private int retry(AMQP.BasicProperties properties) {
        Object value=properties.getHeaders()==null ? null : properties.getHeaders().get("x-billing-retry");
        return value instanceof Number number && number.longValue()>=0 && number.longValue()<=10 ? number.intValue() : 0;
    }
    private void transfer(Channel channel,AtomicBoolean returned,GetResponse source,String target,int retry,String failure) throws Exception {
        var original=source.getProps(); var headers=new HashMap<String,Object>();
        if(original.getHeaders()!=null) headers.putAll(original.getHeaders());
        headers.put("x-billing-retry",retry); headers.remove("x-billing-failure");
        if(failure!=null) headers.put("x-billing-failure",failure);
        var properties=original.builder().headers(headers).deliveryMode(2).expiration(null).build();
        returned.set(false); channel.basicPublish("",target,true,properties,source.getBody());
        channel.waitForConfirmsOrDie(timeout);
        if(returned.get()) throw new Unavailable();
    }
    private ConnectionFactory factory() throws Exception {
        URI uri=URI.create(credential.get());
        if(uri.getHost()==null || uri.getRawUserInfo()==null || !uri.getRawUserInfo().contains(":")
                || uri.getQuery()!=null || uri.getFragment()!=null
                || !("amqps".equals(uri.getScheme()) || local && "amqp".equals(uri.getScheme()))) throw new Unavailable();
        var factory=new ConnectionFactory();
        if("amqps".equals(uri.getScheme())) { factory.useSslProtocol(SSLContext.getDefault()); factory.enableHostnameVerification(); }
        factory.setUri(uri); factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(timeout);
        factory.setHandshakeTimeout(timeout); factory.setChannelRpcTimeout(timeout); factory.setShutdownTimeout(timeout);
        return factory;
    }
    public static class Unavailable extends RuntimeException { public Unavailable() { super("Clinical broker pass unavailable"); } }
}
