package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.client.FreightRateClient;
import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.entity.*;
import com.bhagwat.scm.transportPlanner.enums.*;
import com.bhagwat.scm.transportPlanner.kafka.TransportPlanKafkaProducer;
import com.bhagwat.scm.transportPlanner.orchestrator.CapacitySplitter;
import com.bhagwat.scm.transportPlanner.repository.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles adding new RTS orders to existing DRAFT/PLANNED transport plans,
 * redistributing costs among all sellers in the plan, and deciding when an
 * open (consolidating) plan should stop accepting more sellers and activate.
 *
 * Rules:
 * - DRAFT plan: add freely, no notifications
 * - PLANNED plan: add if capacity allows, notify all sellers of cost change
 * - ACTIVE plan: cannot modify, new RTS gets a new plan
 *
 * A plan auto-activates — closing it to further consolidation — the moment
 * ANY of these trips, whichever comes first:
 *   1. Real vehicle capacity (largest carrier vehicle on file) is reached
 *   2. Seller count reaches transport.planning.max-sellers-per-plan
 *   3. Its plannedStartDateTime arrives (checked by the scheduled sweep below)
 * Without this, a plan would otherwise sit open indefinitely waiting for
 * more sellers to consolidate into it, with only a human's manual
 * "activate" click ever closing it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanAmendmentService {

    private final TransportPlanRepository planRepo;
    private final TransportPlanKafkaProducer kafkaProducer;
    private final FreightRateClient freightRateClient;
    private final CapacitySplitter capacitySplitter;
    private final TransportPlanService planService;

    @Value("${transport.planning.max-sellers-per-plan:3}")
    private int maxSellersPerPlan;

    @Data @Builder
    public static class CostAllocation {
        private String sellerId;
        private String rtsId;
        private BigDecimal weightKg;
        private BigDecimal allocatedCost;
        private BigDecimal previousCost;
        private BigDecimal savings;
    }

    @Data @Builder
    public static class AmendmentResult {
        private String planId;
        private String planNumber;
        private TransportPlanStatus status;
        private boolean added;
        private String message;
        private BigDecimal totalPlanCost;
        private List<CostAllocation> costAllocations;
    }

    /**
     * Attempts to add a new RTS to an existing plan for the same carrier+origin.
     * Returns the result with cost redistribution.
     */
    @Transactional
    public AmendmentResult addRtsToPlan(String planId, TransportPlanOrder newOrder, BigDecimal newWeightKg) {
        TransportPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        // Rule: ACTIVE/COMPLETED plans cannot be modified
        if (plan.getStatus() == TransportPlanStatus.ACTIVE || plan.getStatus() == TransportPlanStatus.COMPLETED) {
            return AmendmentResult.builder()
                    .planId(planId).planNumber(plan.getPlanNumber())
                    .status(plan.getStatus()).added(false)
                    .message("Plan is " + plan.getStatus() + ". Cannot modify. Create a new plan for this RTS.")
                    .build();
        }

        // Check vehicle capacity against this carrier's actual fleet, not a flat guess
        BigDecimal currentWeight = plan.getTotalWeightKg() != null ? plan.getTotalWeightKg() : BigDecimal.ZERO;
        BigDecimal maxCapacity = capacitySplitter.getMaxVehicleCapacity(plan.getCarrierId(), plan.getTransportMode());
        if (currentWeight.add(newWeightKg).compareTo(maxCapacity) > 0) {
            return AmendmentResult.builder()
                    .planId(planId).planNumber(plan.getPlanNumber())
                    .status(plan.getStatus()).added(false)
                    .message("Vehicle capacity exceeded. Current: " + currentWeight + "kg + new: " + newWeightKg + "kg > max: " + maxCapacity + "kg")
                    .build();
        }

        // Add the new order to the plan
        plan.getOrders().add(newOrder);
        plan.setTotalWeightKg(currentWeight.add(newWeightKg));

        // Update rtsIds
        String existingIds = plan.getRtsIds() != null ? plan.getRtsIds() : "";
        plan.setRtsIds(existingIds.isEmpty() ? newOrder.getRtsItemId() : existingIds + "," + newOrder.getRtsItemId());

        planRepo.save(plan);

        // Recalculate cost distribution
        List<CostAllocation> allocations = recalculateCosts(plan);

        // If plan is PLANNED, notify sellers of cost change
        if (plan.getStatus() == TransportPlanStatus.PLANNED) {
            notifySellersOfCostChange(plan, allocations);
        }

        return AmendmentResult.builder()
                .planId(planId).planNumber(plan.getPlanNumber())
                .status(plan.getStatus()).added(true)
                .message("RTS added to plan. Cost redistributed among " + allocations.size() + " sellers.")
                .totalPlanCost(plan.getTotalWeightKg()) // placeholder for actual cost
                .costAllocations(allocations)
                .build();
    }

    /**
     * Checks whether a still-open (DRAFT/PLANNED) plan has hit its consolidation
     * limit — real vehicle capacity, or the configured max-sellers-per-plan —
     * and if so, activates it immediately instead of leaving it open for more
     * sellers. Called after every successful addRtsToPlan() and after a fresh
     * plan is created from a batch that's already at the limit. Failures to
     * activate (e.g. no vehicle currently available) are logged, not thrown —
     * the plan just stays open for the next attempt (a later add, or the
     * plannedStartDateTime cutoff in closeStalePlans() below).
     */
    @Transactional
    public void maybeAutoActivate(String planId) {
        TransportPlan plan = planRepo.findById(planId).orElse(null);
        if (plan == null) return;
        if (plan.getStatus() != TransportPlanStatus.DRAFT && plan.getStatus() != TransportPlanStatus.PLANNED) return;

        long sellerCount = plan.getOrders().stream()
                .map(TransportPlanOrder::getOrderNumber)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        BigDecimal totalWeight = plan.getTotalWeightKg() != null ? plan.getTotalWeightKg() : BigDecimal.ZERO;
        BigDecimal maxCapacity = capacitySplitter.getMaxVehicleCapacity(plan.getCarrierId(), plan.getTransportMode());

        boolean capacityFull = totalWeight.compareTo(maxCapacity) >= 0;
        boolean maxSellersReached = sellerCount >= maxSellersPerPlan;
        if (!capacityFull && !maxSellersReached) return;

        log.info("Auto-activating plan {} — {} (sellers={}/{}, weight={}kg/{}kg)",
                plan.getPlanNumber(), capacityFull ? "vehicle capacity reached" : "max sellers reached",
                sellerCount, maxSellersPerPlan, totalWeight, maxCapacity);
        try {
            planService.activatePlan(planId);
        } catch (Exception e) {
            log.warn("Auto-activation deferred for plan {} (will retry on next add or cutoff sweep): {}",
                    plan.getPlanNumber(), e.getMessage());
        }
    }

    /**
     * Cutoff side of consolidation: a plan shouldn't sit open forever waiting
     * for one more seller. Every plan already gets a plannedStartDateTime when
     * created (see ContractBasedPlanningService); once that arrives, close and
     * activate the plan as-is, regardless of whether it ever hit capacity.
     */
    @Scheduled(fixedDelayString = "${transport.planning.cutoff-check-interval-ms:900000}")
    public void closeStalePlans() {
        List<TransportPlan> due = planRepo.findByStatusInAndPlannedStartDateTimeBefore(
                List.of(TransportPlanStatus.DRAFT, TransportPlanStatus.PLANNED), LocalDateTime.now());
        for (TransportPlan plan : due) {
            log.info("Plan {} reached its plannedStartDateTime ({}) while still open — auto-activating as-is",
                    plan.getPlanNumber(), plan.getPlannedStartDateTime());
            try {
                planService.activatePlan(plan.getPlanId());
            } catch (Exception e) {
                log.warn("Could not auto-activate plan {} at cutoff: {}", plan.getPlanNumber(), e.getMessage());
            }
        }
    }

    /**
     * Find an existing DRAFT/PLANNED plan that matches carrier+origin for consolidation.
     */
    public Optional<TransportPlan> findConsolidatablePlan(String carrierId, String originCity) {
        return planRepo.findAll().stream()
                .filter(p -> (p.getStatus() == TransportPlanStatus.DRAFT || p.getStatus() == TransportPlanStatus.PLANNED))
                .filter(p -> carrierId.equals(p.getCarrierId()))
                .filter(p -> p.getOriginLocation() != null && originCity.equalsIgnoreCase(p.getOriginLocation().getCity()))
                .findFirst();
    }

    /**
     * Recalculate cost allocation pro-rata by weight.
     * Formula: seller_cost = (seller_weight / total_weight) × total_plan_cost
     */
    private List<CostAllocation> recalculateCosts(TransportPlan plan) {
        BigDecimal totalWeight = plan.getTotalWeightKg() != null ? plan.getTotalWeightKg() : BigDecimal.ONE;

        // Get distance (use totalDistanceKm if populated, otherwise estimate)
        BigDecimal totalDistance = plan.getTotalDistanceKm() != null ? plan.getTotalDistanceKm() : BigDecimal.valueOf(500);

        // Get contracted rate from contractManager (replaces hardcoded 2.10)
        String originPin = plan.getOriginLocation() != null ? plan.getOriginLocation().getPincode() : null;
        String destPin = plan.getDestinationLocation() != null ? plan.getDestinationLocation().getPincode() : null;
        BigDecimal ratePerKm = freightRateClient.getFreightRate(
                plan.getCarrierId(), originPin, destPin, totalWeight);
        BigDecimal totalCost = totalDistance.multiply(ratePerKm);

        // Group orders by seller (via rtsId prefix or stored sellerId)
        Map<String, BigDecimal> weightBySeller = new HashMap<>();
        Map<String, String> rtsIdBySeller = new HashMap<>();

        for (TransportPlanOrder order : plan.getOrders()) {
            String sellerId = order.getOrderNumber() != null ? order.getOrderNumber() : "UNKNOWN";
            BigDecimal orderWeight = order.getWeightKg() != null ? order.getWeightKg() : BigDecimal.ZERO;
            weightBySeller.merge(sellerId, orderWeight, BigDecimal::add);
            rtsIdBySeller.putIfAbsent(sellerId, order.getRtsItemId());
        }

        return weightBySeller.entrySet().stream().map(entry -> {
            String sellerId = entry.getKey();
            BigDecimal sellerWeight = entry.getValue();
            BigDecimal proportion = sellerWeight.divide(totalWeight, 4, RoundingMode.HALF_UP);
            BigDecimal allocatedCost = totalCost.multiply(proportion).setScale(2, RoundingMode.HALF_UP);

            return CostAllocation.builder()
                    .sellerId(sellerId)
                    .rtsId(rtsIdBySeller.get(sellerId))
                    .weightKg(sellerWeight)
                    .allocatedCost(allocatedCost)
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * Notify all sellers in the plan about cost changes (only for PLANNED state).
     */
    private void notifySellersOfCostChange(TransportPlan plan, List<CostAllocation> allocations) {
        for (CostAllocation alloc : allocations) {
            kafkaProducer.publishPlanUpdated(plan.getPlanId(), Map.of(
                    "event", "COST_UPDATED",
                    "planId", plan.getPlanId(),
                    "planNumber", plan.getPlanNumber(),
                    "sellerId", alloc.getSellerId(),
                    "rtsId", alloc.getRtsId() != null ? alloc.getRtsId() : "",
                    "newCost", alloc.getAllocatedCost().toString(),
                    "totalWeight", plan.getTotalWeightKg().toString(),
                    "message", "Shipment consolidated. Your cost updated due to shared vehicle."
            ));
        }
        log.info("Notified {} sellers of cost change for plan {}", allocations.size(), plan.getPlanNumber());
    }
}
