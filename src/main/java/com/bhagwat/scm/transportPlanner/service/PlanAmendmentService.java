package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.client.FreightRateClient;
import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.entity.*;
import com.bhagwat.scm.transportPlanner.enums.*;
import com.bhagwat.scm.transportPlanner.kafka.TransportPlanKafkaProducer;
import com.bhagwat.scm.transportPlanner.repository.*;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles adding new RTS orders to existing DRAFT/PLANNED transport plans
 * and redistributing costs among all sellers in the plan.
 *
 * Rules:
 * - DRAFT plan: add freely, no notifications
 * - PLANNED plan: add if capacity allows, notify all sellers of cost change
 * - ACTIVE plan: cannot modify, new RTS gets a new plan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanAmendmentService {

    private final TransportPlanRepository planRepo;
    private final TransportPlanKafkaProducer kafkaProducer;
    private final FreightRateClient freightRateClient;

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

        // Check vehicle capacity (simple weight check)
        BigDecimal currentWeight = plan.getTotalWeightKg() != null ? plan.getTotalWeightKg() : BigDecimal.ZERO;
        BigDecimal maxCapacity = BigDecimal.valueOf(10000); // Default truck capacity
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
