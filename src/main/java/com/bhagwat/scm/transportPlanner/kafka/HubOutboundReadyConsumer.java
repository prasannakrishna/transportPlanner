package com.bhagwat.scm.transportPlanner.kafka;

import com.bhagwat.scm.transportPlanner.orchestrator.HubOperationsCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes hub.outbound.ready events from wmsService.
 *
 * Published when wmsService completes all hub operations (unload, sort, repackage, load)
 * for a consignment at the cross-dock facility.
 *
 * Event payload:
 * {
 *   "eventType": "HUB_OUTBOUND_READY",
 *   "consignmentId": "CON-001",
 *   "outboundLegId": "leg-yyy",
 *   "hubLocationId": "WH-BLR-01",
 *   "processedItems": 45,
 *   "timestamp": "2026-08-04T14:00:00Z"
 * }
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HubOutboundReadyConsumer {

    private final HubOperationsCoordinator hubCoordinator;

    @KafkaListener(topics = "hub.outbound.ready", groupId = "transport-planner")
    public void onHubOutboundReady(Map<String, Object> event) {
        try {
            String consignmentId = (String) event.get("consignmentId");
            String outboundLegId = (String) event.get("outboundLegId");

            if (consignmentId == null || outboundLegId == null) {
                log.warn("Incomplete hub.outbound.ready event — missing consignmentId or outboundLegId");
                return;
            }

            log.info("Received hub.outbound.ready: consignment={} leg={} hub={}",
                    consignmentId, outboundLegId, event.get("hubLocationId"));

            hubCoordinator.onHubOutboundReady(consignmentId, outboundLegId);

        } catch (Exception e) {
            log.error("Error processing hub.outbound.ready: {}", e.getMessage(), e);
        }
    }
}
