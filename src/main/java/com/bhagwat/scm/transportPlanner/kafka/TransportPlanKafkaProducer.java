package com.bhagwat.scm.transportPlanner.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
public class TransportPlanKafkaProducer {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPlanCreated(String planId, Object payload) {
        send("transport.plan.created", planId, payload);
    }

    public void publishPlanUpdated(String planId, Object payload) {
        send("transport.plan.updated", planId, payload);
    }

    public void publishTransportOrderCreated(Object transportOrder) {
        send("transport.order.created", null, transportOrder);
    }

    private void send(String topic, String key, Object payload) {
        try {
            kafkaTemplate.send(topic, key, payload);
            log.info("Published to {} key={}", topic, key);
        } catch (Exception e) {
            log.error("Failed to publish to {}: {}", topic, e.getMessage());
        }
    }
}
