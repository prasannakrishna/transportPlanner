package com.bhagwat.scm.transportPlanner.kafka;

import com.bhagwat.scm.transportPlanner.service.ContractBasedPlanningService;
import com.bhagwat.scm.transportPlanner.service.TransportPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes RTS created events from carrierService.
 *
 * Two strategies:
 * 1. IMMEDIATE (demand-based): If planning.mode=IMMEDIATE, creates a DIRECT plan instantly.
 *    Used when seller explicitly selects a carrier for a single shipment.
 *
 * 2. BATCHED (contract-based): If planning.mode=BATCHED, queues the RTS for the next
 *    planning run which groups multiple orders for efficiency.
 *    The ContractBasedPlanningService runs on cron and picks these up.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RtsCreatedConsumer {

    private final TransportPlanService planService;
    private final ContractBasedPlanningService batchPlanner;

    @Value("${transport.planning.mode:IMMEDIATE}")
    private String planningMode;

    @KafkaListener(topics = "transport.rts.created", groupId = "transport-planner")
    @SuppressWarnings("unchecked")
    public void onRtsCreated(Map<String, Object> event) {
        try {
            String rtsId = (String) event.get("rtsId");
            String carrierId = (String) event.get("carrierId");
            if (rtsId == null || carrierId == null) return;

            log.info("Received RTS created: rtsId={} carrierId={} mode={}", rtsId, carrierId, planningMode);

            // Extract allocation scoring context if present (from allocationService Phase 6)
            Map<String, Object> scoringContext = (Map<String, Object>) event.get("scoringContext");
            if (scoringContext != null) {
                log.info("RTS {} has scoring context: sourceId={} score={} version={}",
                        rtsId, scoringContext.get("selectedSourceId"),
                        scoringContext.get("finalScore"), scoringContext.get("scoringVersion"));
            }

            if ("BATCHED".equalsIgnoreCase(planningMode)) {
                log.info("BATCHED mode: RTS {} queued for next planning run", rtsId);
                batchPlanner.triggerPlanningRun();
            } else {
                // IMMEDIATE mode: create DIRECT plan right away (demand-based)
                String shipmentType = (String) event.get("shipmentType");
                planService.autoCreatePlanFromRts(rtsId, carrierId, shipmentType, event);
            }
        } catch (Exception e) {
            log.error("Error processing rts.created: {}", e.getMessage());
        }
    }
}
