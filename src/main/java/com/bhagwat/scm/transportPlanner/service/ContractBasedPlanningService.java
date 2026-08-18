package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.entity.TransportPlan;
import com.bhagwat.scm.transportPlanner.entity.TransportPlanOrder;
import com.bhagwat.scm.transportPlanner.enums.*;
import com.bhagwat.scm.transportPlanner.orchestrator.PlanTypeCostEstimator;
import com.bhagwat.scm.core.rest.api.ApiClient;
import com.bhagwat.scm.core.rest.config.ServiceApiRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Contract-Based Planning Engine (Mode 1).
 *
 * Runs on schedule (e.g., every hour or at configured planning windows).
 * Collects all READY/BOOKED RTS orders, groups them by carrier+origin+destination,
 * and creates optimized Transport Plans.
 *
 * Enterprise TMS pattern: "Planning Run" that batches shipments for efficiency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractBasedPlanningService {

    private final TransportPlanService planService;
    private final PlanAmendmentService amendmentService;
    private final PlanTypeCostEstimator costEstimator;
    private final ApiClient apiClient;
    private final ServiceApiRegistry registry;

    /**
     * Scheduled planning run — collects unplanned RTS orders and creates plans.
     * Runs every hour (configurable). Can also be triggered manually via API.
     */
    @Scheduled(cron = "${transport.planning.cron:0 0 * * * *}")
    public void runPlanningCycle() {
        log.info("=== Starting transport planning cycle ===");
        try {
            List<Map<String, Object>> readyOrders = fetchUnplannedRtsOrders();
            if (readyOrders.isEmpty()) {
                log.info("No unplanned RTS orders found. Skipping.");
                return;
            }

            log.info("Found {} unplanned RTS orders. Grouping...", readyOrders.size());

            // Group by carrier → origin → destinations
            Map<String, List<Map<String, Object>>> byCarrier = readyOrders.stream()
                    .filter(r -> r.get("carrierId") != null)
                    .collect(Collectors.groupingBy(r -> (String) r.get("carrierId")));

            for (Map.Entry<String, List<Map<String, Object>>> carrierGroup : byCarrier.entrySet()) {
                String carrierId = carrierGroup.getKey();
                List<Map<String, Object>> orders = carrierGroup.getValue();

                // Sub-group by origin
                Map<String, List<Map<String, Object>>> byOrigin = orders.stream()
                        .collect(Collectors.groupingBy(r -> getCity(r, "orig_city")));

                for (Map.Entry<String, List<Map<String, Object>>> originGroup : byOrigin.entrySet()) {
                    createPlanForGroup(carrierId, originGroup.getKey(), originGroup.getValue());
                }
            }

            log.info("=== Planning cycle complete ===");
        } catch (Exception e) {
            log.error("Planning cycle failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for planning run (called via API).
     */
    public int triggerPlanningRun() {
        List<Map<String, Object>> readyOrders = fetchUnplannedRtsOrders();
        if (readyOrders.isEmpty()) return 0;

        int plansCreated = 0;
        Map<String, List<Map<String, Object>>> byCarrier = readyOrders.stream()
                .filter(r -> r.get("carrierId") != null)
                .collect(Collectors.groupingBy(r -> (String) r.get("carrierId")));

        for (var carrierGroup : byCarrier.entrySet()) {
            Map<String, List<Map<String, Object>>> byOrigin = carrierGroup.getValue().stream()
                    .collect(Collectors.groupingBy(r -> getCity(r, "orig_city")));
            for (var originGroup : byOrigin.entrySet()) {
                createPlanForGroup(carrierGroup.getKey(), originGroup.getKey(), originGroup.getValue());
                plansCreated++;
            }
        }
        return plansCreated;
    }

    private void createPlanForGroup(String carrierId, String originCity, List<Map<String, Object>> orders) {
        // Determine plan type based on cost comparison across all feasible types
        Set<String> origins = orders.stream()
                .map(r -> getCity(r, "orig_city"))
                .collect(Collectors.toSet());
        Set<String> destinations = orders.stream()
                .map(r -> getCity(r, "dest_city"))
                .collect(Collectors.toSet());

        // Build RtsInfo list for cost estimator
        List<RtsInfo> rtsInfos = orders.stream().map(r -> {
            String rtsId = (String) r.get("rtsId");
            return RtsInfo.builder()
                    .rtsId(rtsId)
                    .rtsNumber((String) r.get("rtsNumber"))
                    .sellerId((String) r.get("sellerId"))
                    .sellerContractId((String) r.get("sellerContractId"))
                    .sourceLocation(PlanLocationDto.builder()
                            .locationId((String) r.get("orig_location_id"))
                            .city(getCity(r, "orig_city"))
                            .state((String) r.get("orig_state"))
                            .pincode((String) r.get("orig_pincode"))
                            .locationType(LocationType.SELLER)
                            .build())
                    .destinationLocation(PlanLocationDto.builder()
                            .locationId((String) r.get("dest_location_id"))
                            .city(getCity(r, "dest_city"))
                            .state((String) r.get("dest_state"))
                            .pincode((String) r.get("dest_pincode"))
                            .locationType(LocationType.STORE)
                            .build())
                    .totalWeightKg(toBd(r.get("totalWeightKg")))
                    .totalVolumeM3(toBd(r.get("totalVolumeM3")))
                    .totalPackages(toInt(r.get("totalPackages")))
                    .build();
        }).toList();

        // Use cost estimator to select optimal plan type (replaces simple origin/dest count)
        String hubPincode = null; // TODO: resolve from spaceService or config
        PlanTypeCostEstimator.PlanTypeSelection selection = costEstimator.selectOptimalType(rtsInfos, carrierId, hubPincode);
        PlanType planType = selection.getSelectedType();

        log.info("Cost-based plan type selection: carrier={} origin={} → {} (reason: {})",
                carrierId, originCity, planType, selection.getSelectionReason());

        String carrierName = (String) orders.get(0).getOrDefault("carrierName", carrierId);

        // Check if there's an existing DRAFT/PLANNED plan we can consolidate into
        Optional<TransportPlan> existingPlan = amendmentService.findConsolidatablePlan(carrierId, originCity);
        if (existingPlan.isPresent()) {
            TransportPlan plan = existingPlan.get();
            log.info("Found existing {} plan {} for carrier={} origin={}. Adding {} orders.",
                    plan.getStatus(), plan.getPlanNumber(), carrierId, originCity, orders.size());

            for (Map<String, Object> order : orders) {
                TransportPlanOrder newOrder = TransportPlanOrder.builder()
                        .planId(plan.getPlanId())
                        .rtsItemId((String) order.get("rtsId"))
                        .orderNumber((String) order.get("sellerId"))
                        .skuId((String) order.get("skuId"))
                        .weightKg(toBd(order.get("totalWeightKg")))
                        .build();
                amendmentService.addRtsToPlan(plan.getPlanId(), newOrder, toBd(order.get("totalWeightKg")));
            }
            return;
        }

        CreateTransportPlanRequest req = CreateTransportPlanRequest.builder()
                .planType(planType)
                .carrierId(carrierId)
                .carrierName(carrierName)
                .shipmentType(ShipmentType.ORDER_TO_STORE)
                .transportMode(TransportMode.ROAD)
                .loadType(orders.size() > 3 ? LoadType.FTL : LoadType.LTL)
                .rtsOrders(rtsInfos)
                .plannedStartDateTime(LocalDateTime.now().plusHours(2))
                .notes("Auto-planned: " + orders.size() + " orders, " + destinations.size() + " destinations"
                        + " | typeSelection: " + selection.getSelectionReason())
                .build();

        try {
            TransportPlanResponse plan = planService.createPlan(req);

            // Store cost comparison in plan custom_data for audit
            // (plan service stores it during creation via customData field on the entity)
            log.info("Created {} plan {} for carrier={} origin={} with {} orders → {} destinations (cost-optimized)",
                    planType, plan.getPlanNumber(), carrierId, originCity, orders.size(), destinations.size());
        } catch (Exception e) {
            log.error("Failed to create plan for carrier={} origin={}: {}", carrierId, originCity, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchUnplannedRtsOrders() {
        try {
            org.springframework.http.ResponseEntity<Map[]> resp = apiClient.invoke(
                    registry.getConfig("carrier-rts-booked"), Map[].class);
            Map[] result = resp.getBody();
            if (result == null) return List.of();
            return Arrays.asList(result);
        } catch (Exception e) {
            log.warn("Could not fetch RTS orders from carrierService: {}", e.getMessage());
            return List.of();
        }
    }

    private String getCity(Map<String, Object> r, String key) {
        Object val = r.get(key);
        if (val != null) return val.toString();
        // Try nested originAddress/destinationAddress
        if (key.startsWith("orig")) {
            Map<String, Object> addr = (Map<String, Object>) r.get("originAddress");
            return addr != null ? (String) addr.get("city") : "UNKNOWN";
        }
        Map<String, Object> addr = (Map<String, Object>) r.get("destinationAddress");
        return addr != null ? (String) addr.get("city") : "UNKNOWN";
    }

    private BigDecimal toBd(Object val) {
        if (val == null) return BigDecimal.ZERO;
        return new BigDecimal(val.toString());
    }

    private Integer toInt(Object val) {
        if (val == null) return 0;
        return ((Number) val).intValue();
    }
}
