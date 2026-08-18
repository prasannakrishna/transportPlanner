package com.bhagwat.scm.transportPlanner.orchestrator;

import com.bhagwat.scm.transportPlanner.client.CarrierNetworkClient;
import com.bhagwat.scm.transportPlanner.client.ContractManagerClient;
import com.bhagwat.scm.transportPlanner.client.FreightRateClient;
import com.bhagwat.scm.transportPlanner.dto.PlanLocationDto;
import com.bhagwat.scm.transportPlanner.dto.RtsInfo;
import com.bhagwat.scm.transportPlanner.enums.PlanType;
import com.bhagwat.scm.transportPlanner.service.DistanceCalculator;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Estimates transport cost for each feasible plan type and selects the cheapest.
 *
 * Evaluates all 4 plan types:
 *   DIRECT                  — N separate direct trips (source→dest each)
 *   SOURCE_CONSOLIDATION    — collect from N sources, deliver to 1 dest
 *   DESTINATION_CONSOLIDATION — pickup once, deliver to N dests (multi-drop)
 *   CROSSDOCK              — N sources → hub → N dests (hub-and-spoke)
 *
 * For each feasible type, calculates:
 *   totalCost = Σ (legDistance × freightRate) for each leg in that plan shape
 *
 * Selects the type with lowest cost. Stores all estimates in planTypeComparison.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanTypeCostEstimator {

    private final FreightRateClient freightRateClient;
    private final DistanceCalculator distanceCalculator;
    private final ContractManagerClient contractManagerClient;
    private final CarrierNetworkClient carrierNetworkClient;

    /**
     * Select the optimal plan type for a group of RTS orders.
     *
     * @param rtsOrders  grouped RTS orders (same carrier, compatible routes)
     * @param carrierId  assigned carrier
     * @param hubPincode optional hub pincode for CROSSDOCK (null if no hub available)
     * @return selection result with chosen type, cost estimates, and feasibility status
     */
    public PlanTypeSelection selectOptimalType(List<RtsInfo> rtsOrders, String carrierId, String hubPincode) {
        if (rtsOrders == null || rtsOrders.isEmpty()) {
            return PlanTypeSelection.builder()
                    .selectedType(PlanType.DIRECT)
                    .selectionReason("No RTS orders provided")
                    .costEstimates(Map.of())
                    .feasibilityStatus(Map.of())
                    .build();
        }

        Set<String> origins = rtsOrders.stream()
                .map(r -> getPincode(r.getSourceLocation()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> destinations = rtsOrders.stream()
                .map(r -> getPincode(r.getDestinationLocation()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        BigDecimal totalWeight = rtsOrders.stream()
                .map(r -> r.getTotalWeightKg() != null ? r.getTotalWeightKg() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<PlanType, BigDecimal> costEstimates = new LinkedHashMap<>();
        Map<PlanType, String> feasibilityStatus = new LinkedHashMap<>();

        // ── Evaluate DIRECT ─────────────────────────────────────────────────
        BigDecimal directCost = estimateDirectCost(rtsOrders, carrierId, totalWeight);
        costEstimates.put(PlanType.DIRECT, directCost);
        feasibilityStatus.put(PlanType.DIRECT, "FEASIBLE");

        // ── Evaluate SOURCE_CONSOLIDATION ────────────────────────────────────
        if (origins.size() > 1 && destinations.size() == 1) {
            BigDecimal srcConsolCost = estimateSourceConsolidationCost(rtsOrders, carrierId, totalWeight, destinations.iterator().next());
            costEstimates.put(PlanType.SOURCE_CONSOLIDATION, srcConsolCost);
            feasibilityStatus.put(PlanType.SOURCE_CONSOLIDATION, "FEASIBLE");
        } else {
            feasibilityStatus.put(PlanType.SOURCE_CONSOLIDATION,
                    "NOT_FEASIBLE: requires N origins + 1 destination (got " + origins.size() + " origins, " + destinations.size() + " dests)");
        }

        // ── Evaluate DESTINATION_CONSOLIDATION ───────────────────────────────
        if (origins.size() == 1 && destinations.size() > 1) {
            BigDecimal destConsolCost = estimateDestConsolidationCost(rtsOrders, carrierId, totalWeight, origins.iterator().next());
            costEstimates.put(PlanType.DESTINATION_CONSOLIDATION, destConsolCost);
            feasibilityStatus.put(PlanType.DESTINATION_CONSOLIDATION, "FEASIBLE");
        } else if (origins.size() >= 1 && destinations.size() > 1) {
            // Also feasible if single dominant origin
            BigDecimal destConsolCost = estimateDestConsolidationCost(rtsOrders, carrierId, totalWeight, origins.iterator().next());
            costEstimates.put(PlanType.DESTINATION_CONSOLIDATION, destConsolCost);
            feasibilityStatus.put(PlanType.DESTINATION_CONSOLIDATION, "FEASIBLE");
        } else {
            feasibilityStatus.put(PlanType.DESTINATION_CONSOLIDATION,
                    "NOT_FEASIBLE: requires 1 origin + N destinations");
        }

        // ── Evaluate CROSSDOCK ───────────────────────────────────────────────
        if (origins.size() > 1 && destinations.size() > 1 && hubPincode != null) {
            // Check partner network consent for all sellers
            boolean allConsented = rtsOrders.stream().allMatch(rts ->
                    rts.getSellerContractId() == null
                            || contractManagerClient.isPartnerNetworkAllowed(rts.getSellerContractId()));

            if (allConsented) {
                BigDecimal crossdockCost = estimateCrossdockCost(rtsOrders, carrierId, totalWeight, hubPincode, origins, destinations);
                costEstimates.put(PlanType.CROSSDOCK, crossdockCost);
                feasibilityStatus.put(PlanType.CROSSDOCK, "FEASIBLE");
            } else {
                feasibilityStatus.put(PlanType.CROSSDOCK,
                        "NOT_FEASIBLE: not all sellers have allowPartnerNetwork=true");
            }
        } else {
            String reason = hubPincode == null ? "no hub available"
                    : "requires N origins + N destinations (got " + origins.size() + "→" + destinations.size() + ")";
            feasibilityStatus.put(PlanType.CROSSDOCK, "NOT_FEASIBLE: " + reason);
        }

        // ── Select cheapest feasible type ────────────────────────────────────
        PlanType selected = PlanType.DIRECT;
        BigDecimal lowestCost = directCost;
        String reason;

        if (costEstimates.size() == 1) {
            reason = "singleFeasibleType";
        } else {
            for (Map.Entry<PlanType, BigDecimal> entry : costEstimates.entrySet()) {
                if (entry.getValue().compareTo(lowestCost) < 0) {
                    lowestCost = entry.getValue();
                    selected = entry.getKey();
                }
            }
            final PlanType finalSelected = selected;
            reason = "lowestCost: " + selected + " at ₹" + lowestCost.setScale(0, RoundingMode.HALF_UP)
                    + " (vs " + costEstimates.entrySet().stream()
                    .filter(e -> e.getKey() != finalSelected)
                    .map(e -> e.getKey() + "=₹" + e.getValue().setScale(0, RoundingMode.HALF_UP))
                    .collect(Collectors.joining(", ")) + ")";
        }

        log.info("PlanType selection: {} — {} feasible types, selected={} cost={}",
                reason, costEstimates.size(), selected, lowestCost);

        return PlanTypeSelection.builder()
                .selectedType(selected)
                .costEstimates(costEstimates)
                .feasibilityStatus(feasibilityStatus)
                .selectionReason(reason)
                .build();
    }

    // ── Cost estimation per plan type ────────────────────────────────────────

    /**
     * DIRECT: Each RTS gets its own trip. Total = sum of individual trip costs.
     * This is the WORST case (most expensive) but fastest.
     */
    private BigDecimal estimateDirectCost(List<RtsInfo> rtsOrders, String carrierId, BigDecimal totalWeight) {
        BigDecimal total = BigDecimal.ZERO;
        for (RtsInfo rts : rtsOrders) {
            String origPin = getPincode(rts.getSourceLocation());
            String destPin = getPincode(rts.getDestinationLocation());
            DistanceCalculator.RouteMetrics metrics = distanceCalculator.calculate(origPin, destPin);
            BigDecimal weight = rts.getTotalWeightKg() != null ? rts.getTotalWeightKg() : BigDecimal.valueOf(100);
            BigDecimal legCost = freightRateClient.calculateLegCost(carrierId, origPin, destPin, metrics.getDistanceKm(), weight);
            total = total.add(legCost);
        }
        return total;
    }

    /**
     * SOURCE_CONSOLIDATION: Multiple pickups → 1 destination.
     * Cost = sum of (each source → dest) but shared vehicle = roughly same distance, lower rate.
     * Approximation: total distance of all pickups + one delivery. Shared vehicle = 0.85× factor.
     */
    private BigDecimal estimateSourceConsolidationCost(List<RtsInfo> rtsOrders, String carrierId,
                                                       BigDecimal totalWeight, String destPincode) {
        BigDecimal total = BigDecimal.ZERO;
        for (RtsInfo rts : rtsOrders) {
            String origPin = getPincode(rts.getSourceLocation());
            DistanceCalculator.RouteMetrics metrics = distanceCalculator.calculate(origPin, destPincode);
            total = total.add(metrics.getDistanceKm());
        }
        BigDecimal rate = freightRateClient.getFreightRate(carrierId, null, destPincode, totalWeight);
        // Consolidation factor: shared vehicle costs ~85% of sum of individual
        return total.multiply(rate).multiply(BigDecimal.valueOf(0.85)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * DESTINATION_CONSOLIDATION: 1 pickup → N deliveries (multi-drop).
     * Cost = pickup distance + sum of inter-destination hops.
     * Approximation: total route distance × rate × 0.9 factor (multi-drop is slightly cheaper).
     */
    private BigDecimal estimateDestConsolidationCost(List<RtsInfo> rtsOrders, String carrierId,
                                                     BigDecimal totalWeight, String originPincode) {
        BigDecimal totalDist = BigDecimal.ZERO;
        for (RtsInfo rts : rtsOrders) {
            String destPin = getPincode(rts.getDestinationLocation());
            DistanceCalculator.RouteMetrics metrics = distanceCalculator.calculate(originPincode, destPin);
            totalDist = totalDist.add(metrics.getDistanceKm());
        }
        BigDecimal rate = freightRateClient.getFreightRate(carrierId, originPincode, null, totalWeight);
        // Multi-drop factor: ~90% of sum (shared first-mile)
        return totalDist.multiply(rate).multiply(BigDecimal.valueOf(0.90)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * CROSSDOCK: N sources → Hub → N destinations.
     * Cost = sum(source→hub distances) × rate + sum(hub→dest distances) × rate.
     * Cheapest when there are many sources and destinations (maximum consolidation at hub).
     * Factor: 0.70 (hub consolidation significantly reduces per-unit cost).
     */
    private BigDecimal estimateCrossdockCost(List<RtsInfo> rtsOrders, String carrierId,
                                              BigDecimal totalWeight, String hubPincode,
                                              Set<String> origins, Set<String> destinations) {
        // First-mile: each unique origin → hub
        BigDecimal firstMileDist = BigDecimal.ZERO;
        for (String originPin : origins) {
            DistanceCalculator.RouteMetrics metrics = distanceCalculator.calculate(originPin, hubPincode);
            firstMileDist = firstMileDist.add(metrics.getDistanceKm());
        }

        // Last-mile: hub → each unique destination
        BigDecimal lastMileDist = BigDecimal.ZERO;
        for (String destPin : destinations) {
            DistanceCalculator.RouteMetrics metrics = distanceCalculator.calculate(hubPincode, destPin);
            lastMileDist = lastMileDist.add(metrics.getDistanceKm());
        }

        BigDecimal totalDist = firstMileDist.add(lastMileDist);
        BigDecimal rate = freightRateClient.getFreightRate(carrierId, hubPincode, null, totalWeight);
        // Crossdock consolidation factor: 0.70 (cheapest option)
        return totalDist.multiply(rate).multiply(BigDecimal.valueOf(0.70)).setScale(2, RoundingMode.HALF_UP);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getPincode(PlanLocationDto loc) {
        return loc != null ? loc.getPincode() : null;
    }

    // ── Result DTO ───────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class PlanTypeSelection {
        private PlanType selectedType;
        private Map<PlanType, BigDecimal> costEstimates;
        private Map<PlanType, String> feasibilityStatus;
        private String selectionReason;
    }
}
