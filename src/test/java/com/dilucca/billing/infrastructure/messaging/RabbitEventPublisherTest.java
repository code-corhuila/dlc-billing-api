package com.dilucca.billing.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.domain.messaging.EventPublisher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RabbitEventPublisherTest {
    @Test void sanitizesMissingMalformedAndUnsafeCredentials() {
        for (String value:new String[] {null,"", "sensitive-invalid-uri", "http://user:secret@example.test",
                "amqp://user:secret@example.test", "amqps://example.test", "amqps://user:secret@example.test?q=secret"}) {
            var publisher=new RabbitEventPublisher(() -> value,"billing.events",Duration.ofMillis(100),false);
            var error=assertThrows(EventPublisher.Unavailable.class,() -> publisher.publish("InvoiceIssued",UUID.randomUUID(),"{}"));
            assertEquals("Event publication unavailable",error.getMessage()); assertNull(error.getCause());
        }
    }
    @Test void sanitizesProviderAndConnectionFailuresWithoutUnboundedRecovery() {
        var broken=new RabbitEventPublisher(() -> { throw new IllegalStateException("private provider failure"); },
                "billing.events",Duration.ofMillis(100),true);
        assertNull(assertThrows(EventPublisher.Unavailable.class,() -> broken.publish("InvoiceIssued",UUID.randomUUID(),"{}")).getCause());
        var offline=new RabbitEventPublisher(() -> "amqp://fixture:private@127.0.0.1:1",
                "billing.events",Duration.ofMillis(100),true);
        assertTimeoutPreemptively(Duration.ofSeconds(3),() ->
                assertThrows(EventPublisher.Unavailable.class,() -> offline.publish("InvoiceIssued",UUID.randomUUID(),"{}")));
    }
    @Test void validatesOperatorSettingsBeforeDispatch() {
        for (String exchange:new String[] {"", "bad exchange", "x".repeat(256)})
            assertThrows(IllegalArgumentException.class,() -> new RabbitEventPublisher(() -> "",exchange,Duration.ofSeconds(1),false));
        for (Duration timeout:new Duration[] {Duration.ZERO,Duration.ofSeconds(-1),Duration.ofSeconds(11)})
            assertThrows(IllegalArgumentException.class,() -> new RabbitEventPublisher(() -> "","billing.events",timeout,false));
    }
}
