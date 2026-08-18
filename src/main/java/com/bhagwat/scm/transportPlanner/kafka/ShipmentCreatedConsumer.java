package com.bhagwat.scm.transportPlanner.kafka;

import com.bhagwat.scm.transportPlanner.service.TransportOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Listens for transport.shipment.created events from carrierService.
 * Links the created TransportShipment back to the TransportOrder.
 */
@Component @RequiredArgsConstructor @Slf4j
public class ShipmentCreatedConsumer {

    private final TransportOrderService toService;

    @KafkaListener(topics = "transport.shipment.created", groupId = "transport-planner")
    public void onShipmentCreated(Map<String, Object> event) {
        try {
            String toId = (String) event.get("toId");
            String tsId = (String) event.get("tsId");
            if (toId != null && tsId != null) {
                toService.linkShipment(toId, tsId);
                log.info("Linked shipment {} to TransportOrder {}", tsId, toId);
            }
        } catch (Exception e) {
            log.error("Error processing shipment.created: {}", e.getMessage());
        }
    }
}
