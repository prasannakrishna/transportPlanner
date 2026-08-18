package com.bhagwat.scm.transportPlanner.orchestrator;

import com.bhagwat.scm.transportPlanner.entity.Consignment;
import com.bhagwat.scm.transportPlanner.entity.TransportOrder;
import com.bhagwat.scm.transportPlanner.entity.TransportPlan;
import com.bhagwat.scm.transportPlanner.entity.TransportPlanLeg;
import com.bhagwat.scm.transportPlanner.enums.LegStatus;
import com.bhagwat.scm.transportPlanner.enums.TransportOrderStatus;
import com.bhagwat.scm.transportPlanner.kafka.TransportPlanKafkaProducer;
import com.bhagwat.scm.transportPlanner.repository.TransportOrderRepository;
import com.bhagwat.scm.transportPlanner.repository.TransportPlanLegRepository;
import com.bhagwat.scm.transportPlanner.repository.TransportPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Coordinates cross-dock hub operations between transportPlanner and wmsService via Kafka.
 *
 * Flow:
 *   1. FIRST_LEG completes at hub → publish "hub.inbound.arrived"
 *   2. wmsService processes: unload → sort → repackage → load
 *   3. wmsService publishes "hub.outbound.ready" per consignment
 *   4. This coordinator receives it → dispatches SECOND_LEG Transport Orders
 *   5. Timeout monitor: if hub doesn't respond within SLA → escalate
 *
 * Kafka Topics:
 *   OUT: hub.inbound.arrived, hub.processing.delayed
 *   IN:  hub.outbound.ready (consumed by HubOutboundReadyConsumer)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HubOperationsCoordinator {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransportPlanRepository planRepo;
    private final TransportPlanLegRepository legRepo;
    private final TransportOrderRepository toRepo;
    private final TransportPlanKafkaProducer planKafkaProducer;

    @Value("${transport.orchestrator.hub-processing-timeout-minutes:240}")
    private int hubTimeoutMinutes;

    private static final String HUB_INBOUND_TOPIC = "hub.inbound.arrived";
    private static final String HUB_DELAYED_TOPIC = "hub.processing.delayed";

    // Track when hub inbound was notified (planId → timestamp)
    private final Map<String, Instant> hubInboundTimestamps = new HashMap<>();

    /**
     * Called when a FIRST_LEG at a hub location transitions to COMPLETED.
     * Publishes hub.inbound.arrived event for wmsService to begin processing.
     */
    public void notifyHubInbound(String planId, String legId, List<String> consignmentIds, String hubLocationId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", "HUB_INBOUND_ARRIVED");
        event.put("planId", planId);
        event.put("legId", legId);
        event.put("hubLocationId", hubLocationId);
        event.put("consignmentIds", consignmentIds);
        event.put("timestamp", Instant.now().toString());

        // Get carrier and weight info from plan
        planRepo.findById(planId).ifPresent(plan -> {
            event.put("carrierId", plan.getCarrierId());
            event.put("totalWeightKg", plan.getTotalWeightKg() != null ? plan.getTotalWeightKg().doubleValue() : 0);
        });

        try {
            kafkaTemplate.send(HUB_INBOUND_TOPIC, planId, event);
            hubInboundTimestamps.put(planId, Instant.now());
            log.info("Published hub.inbound.arrived: planId={} legId={} hub={} consignments={}",
                    planId, legId, hubLocationId, consignmentIds.size());
        } catch (Exception e) {
            log.error("Failed to publish hub.inbound.arrived for plan {}: {}", planId, e.getMessage());
        }
    }

    /**
     * Called when wmsService signals that a consignment is ready for outbound dispatch.
     * Finds the corresponding SECOND_LEG Transport Order and dispatches it.
     */
    @Transactional
    public void onHubOutboundReady(String consignmentId, String outboundLegId) {
        log.info("Hub outbound ready: consignment={} outboundLeg={}", consignmentId, outboundLegId);

        // Find the leg and update status
        legRepo.findById(outboundLegId).ifPresent(leg -> {
            leg.setStatus(LegStatus.IN_TRANSIT);
            leg.setActualPickupDateTime(LocalDateTime.now());
            legRepo.save(leg);

            // Find and dispatch the Transport Order for this leg
            List<TransportOrder> orders = toRepo.findAllByLegId(outboundLegId);
            for (TransportOrder to : orders) {
                if (to.getStatus() == TransportOrderStatus.CREATED
                        || to.getStatus() == TransportOrderStatus.DISPATCHED_TO_CARRIER) {
                    to.setStatus(TransportOrderStatus.IN_EXECUTION);
                    to.setActualPickupDateTime(LocalDateTime.now());
                    toRepo.save(to);

                    // Publish TO to carrier for execution
                    planKafkaProducer.publishTransportOrderCreated(to);
                    log.info("Dispatched second-leg TO {} for consignment {} (carrier={})",
                            to.getToNumber(), consignmentId, to.getCarrierId());
                }
            }

            // Remove from timeout tracking
            if (leg.getPlanId() != null) {
                hubInboundTimestamps.remove(leg.getPlanId());
            }
        });
    }

    /**
     * Scheduled: checks for hub processing that has exceeded the timeout.
     * If wmsService hasn't published hub.outbound.ready within the configured window,
     * escalates with a hub.processing.delayed event.
     */
    @Scheduled(fixedDelay = 300000) // every 5 minutes
    public void checkHubProcessingTimeouts() {
        Instant cutoff = Instant.now().minus(hubTimeoutMinutes, ChronoUnit.MINUTES);

        List<String> timedOut = hubInboundTimestamps.entrySet().stream()
                .filter(e -> e.getValue().isBefore(cutoff))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        for (String planId : timedOut) {
            log.error("Hub processing timeout for plan {}: inbound arrived {}min ago, no outbound ready",
                    planId, hubTimeoutMinutes);

            // Publish escalation event
            Map<String, Object> delayEvent = new LinkedHashMap<>();
            delayEvent.put("eventType", "HUB_PROCESSING_DELAYED");
            delayEvent.put("planId", planId);
            delayEvent.put("timeoutMinutes", hubTimeoutMinutes);
            delayEvent.put("inboundAt", hubInboundTimestamps.get(planId).toString());
            delayEvent.put("escalatedAt", Instant.now().toString());

            planRepo.findById(planId).ifPresent(plan -> {
                delayEvent.put("hubLocationId", plan.getHubLocation() != null
                        ? plan.getHubLocation().getLocationId() : "unknown");
                delayEvent.put("carrierId", plan.getCarrierId());
                delayEvent.put("planNumber", plan.getPlanNumber());
            });

            try {
                kafkaTemplate.send(HUB_DELAYED_TOPIC, planId, delayEvent);
            } catch (Exception e) {
                log.error("Failed to publish hub.processing.delayed: {}", e.getMessage());
            }

            // Remove from tracking to avoid repeated escalations
            hubInboundTimestamps.remove(planId);
        }
    }

    /**
     * Called by ShipmentMilestoneConsumer when a FIRST_LEG milestone=DELIVERED
     * and the plan has a hub location. Determines consignment IDs and triggers hub inbound.
     */
    public void onFirstLegCompletedAtHub(TransportPlan plan, TransportPlanLeg completedLeg) {
        if (plan.getHubLocation() == null) return;

        String hubLocationId = plan.getHubLocation().getLocationId();
        List<String> consignmentIds = plan.getConsignments() != null
                ? plan.getConsignments().stream()
                .map(Consignment::getConsignmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
                : List.of();

        notifyHubInbound(plan.getPlanId(), completedLeg.getLegId(), consignmentIds, hubLocationId);
    }
}
