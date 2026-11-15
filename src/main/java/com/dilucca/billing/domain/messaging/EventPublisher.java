package com.dilucca.billing.domain.messaging;

import java.util.UUID;

@FunctionalInterface
public interface EventPublisher {
    void publish(String routingKey,UUID eventId,String json);
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("Event publication unavailable"); }
    }
}
