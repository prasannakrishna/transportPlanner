package com.bhagwat.scm.transportPlanner.orchestrator;

import com.bhagwat.scm.transportPlanner.entity.TransportPlan;
import com.bhagwat.scm.transportPlanner.entity.TransportPlanLeg;
import com.bhagwat.scm.transportPlanner.enums.LegStatus;
import com.bhagwat.scm.transportPlanner.enums.TransportPlanStatus;
import com.bhagwat.scm.transportPlanner.kafka.TransportPlanKafkaProducer;
import com.bhagwat.scm.transportPlanner.repository.TransportPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Monitors ACTIVE transport plans for leg delays and escalates when legs
 * exceed their planned delivery time by more than a configurable threshold.
 *
 * Also tracks orchestration decisions for audit trail in plan custom_data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanLifecycleMonitor {

    private final TransportPlanRepository planRepo;
    private final TransportPlanKafkaProducer kafkaProducer;

    @Value("${transport.orchestrator.leg-delay-threshold-minutes:60}")
    private int delayThresholdMinutes;

    /**
     * Scheduled: checks ACTIVE plans with IN_TRANSIT legs past their planned delivery time.
     * Escalates if delay exceeds threshold.
     */
    @Scheduled(fixedDelay = 120000) // every 2 minutes
    @Transactional
    public void monitorActivePlans() {
        List<TransportPlan> activePlans = planRepo.findByStatus(TransportPlanStatus.ACTIVE);
        LocalDateTime now = LocalDateTime.now();

        for (TransportPlan plan : activePlans) {
            if (plan.getLegs() == null) continue;

            for (TransportPlanLeg leg : plan.getLegs()) {
                if (leg.getStatus() != LegStatus.IN_TRANSIT) continue;
                if (leg.getPlannedDeliveryDateTime() == null) continue;

                // Check if leg is past its planned delivery + threshold
                LocalDateTime deadline = leg.getPlannedDeliveryDateTime().plusMinutes(delayThresholdMinutes);
                if (now.isAfter(deadline)) {
                    long delayMinutes = java.time.Duration.between(leg.getPlannedDeliveryDateTime(), now).toMinutes();

                    log.warn("Leg {} of plan {} is {}min past planned delivery (threshold: {}min). Escalating.",
                            leg.getLegId(), plan.getPlanNumber(), delayMinutes, delayThresholdMinutes);

                    // Record escalation in plan custom_data
                    recordEscalation(plan, leg, delayMinutes);

                    // Publish notification event
                    kafkaProducer.publishPlanUpdated(plan.getPlanId(), Map.of(
                            "event", "LEG_DELAY_ESCALATION",
                            "planId", plan.getPlanId(),
                            "planNumber", plan.getPlanNumber(),
                            "legId", leg.getLegId(),
                            "legSequence", leg.getLegSequence(),
                            "carrierId", leg.getCarrierId() != null ? leg.getCarrierId() : plan.getCarrierId(),
                            "delayMinutes", delayMinutes,
                            "plannedDelivery", leg.getPlannedDeliveryDateTime().toString(),
                            "escalatedAt", now.toString()
                    ));
                }
            }
        }
    }

    /**
     * Record an orchestration decision in the plan's custom_data for audit.
     */
    @SuppressWarnings("unchecked")
    private void recordEscalation(TransportPlan plan, TransportPlanLeg leg, long delayMinutes) {
        Map<String, Object> customData = plan.getCustomData() != null
                ? new HashMap<>(plan.getCustomData()) : new HashMap<>();

        List<Map<String, Object>> escalations = (List<Map<String, Object>>)
                customData.computeIfAbsent("escalations", k -> new ArrayList<>());

        escalations.add(Map.of(
                "type", "LEG_DELAY",
                "legId", leg.getLegId(),
                "delayMinutes", delayMinutes,
                "carrierId", leg.getCarrierId() != null ? leg.getCarrierId() : "",
                "timestamp", Instant.now().toString()
        ));

        plan.setCustomData(customData);
        planRepo.save(plan);
    }
}
