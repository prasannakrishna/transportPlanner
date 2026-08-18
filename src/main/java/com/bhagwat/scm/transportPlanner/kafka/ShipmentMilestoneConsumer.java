package com.bhagwat.scm.transportPlanner.kafka;

import com.bhagwat.scm.transportPlanner.service.TransportPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component @RequiredArgsConstructor @Slf4j
public class ShipmentMilestoneConsumer {
    private final TransportPlanService planService;

    @KafkaListener(topics = "transport.shipment.milestone", groupId = "transport-planner")
    public void onMilestone(Map<String, Object> event) {
        try {
            String tsId = (String) event.get("tsId");
            String milestone = (String) event.get("milestone");
            if (tsId == null || milestone == null) return;
            log.info("Received shipment milestone: tsId={}, milestone={}", tsId, milestone);
            planService.handleMilestoneEvent(tsId, milestone, event);
        } catch (Exception e) {
            log.error("Error processing shipment.milestone event: {}", e.getMessage());
        }
    }
}
