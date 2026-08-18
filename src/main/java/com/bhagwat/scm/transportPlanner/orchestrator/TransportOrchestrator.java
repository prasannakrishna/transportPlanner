package com.bhagwat.scm.transportPlanner.orchestrator;

import com.bhagwat.scm.transportPlanner.client.FreightRateClient;
import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.enums.PlanType;
import com.bhagwat.scm.transportPlanner.enums.TransportMode;
import com.bhagwat.scm.transportPlanner.service.TransportPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified Transport Orchestrator — the top-level decision-making component
 * that composes all planning intelligence into a single pipeline.
 *
 * Called by:
 *   - ContractBasedPlanningService (BATCHED mode, scheduled)
 *   - RtsCreatedConsumer (ORCHESTRATED mode, event-driven)
 *
 * Pipeline:
 *   1. Distance calculation (per origin→dest pair)
 *   2. Freight rate lookup (from contractManager)
 *   3. Cost-based plan type selection (evaluate all feasible types)
 *   4. Capacity split (if total weight > vehicle capacity)
 *   5. Network route resolution (for CROSSDOCK — per-leg carriers)
 *   6. Plan creation (via TransportPlanService)
 *   7. Orchestration audit (decisions stored in plan custom_data)
 *
 * This does NOT replace existing direct plan creation via REST API.
 * Manual plan creation (POST /api/v1/transport/plans) still bypasses the orchestrator.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransportOrchestrator {

    private final PlanTypeCostEstimator costEstimator;
    private final CapacitySplitter capacitySplitter;
    private final FreightRateClient freightRateClient;
    private final TransportPlanService planService;

    /**
     * Full orchestrated planning pipeline for a group of RTS orders.
     *
     * @param rtsOrders  grouped RTS orders (same carrier, compatible routes)
     * @param carrierId  assigned carrier
     * @param carrierName carrier display name
     * @param hubPincode optional hub pincode for CROSSDOCK consideration
     * @return list of created plan responses (may be multiple if capacity-split)
     */
    public List<TransportPlanResponse> orchestratePlanning(List<RtsInfo> rtsOrders, String carrierId,
                                                            String carrierName, String hubPincode) {
        if (rtsOrders == null || rtsOrders.isEmpty()) {
            log.warn("Orchestrator called with empty RTS orders");
            return List.of();
        }

        log.info("=== Orchestrator: planning {} RTS orders for carrier={} ===", rtsOrders.size(), carrierId);

        // Clear rate cache at start of orchestration cycle
        freightRateClient.clearCache();

        // Step 1: Select optimal plan type via cost comparison
        PlanTypeCostEstimator.PlanTypeSelection typeSelection =
                costEstimator.selectOptimalType(rtsOrders, carrierId, hubPincode);
        PlanType selectedType = typeSelection.getSelectedType();

        log.info("Orchestrator type selection: {} (reason: {})", selectedType, typeSelection.getSelectionReason());

        // Step 2: Capacity split if needed
        List<List<RtsInfo>> groups = capacitySplitter.splitByCapacity(rtsOrders, carrierId, TransportMode.ROAD);

        if (groups.size() > 1) {
            log.info("Orchestrator: split into {} sub-plans (capacity overflow)", groups.size());
        }

        // Step 3: Create plan for each capacity group
        List<TransportPlanResponse> plans = new ArrayList<>();
        for (List<RtsInfo> group : groups) {
            try {
                CreateTransportPlanRequest req = CreateTransportPlanRequest.builder()
                        .planType(selectedType)
                        .carrierId(carrierId)
                        .carrierName(carrierName)
                        .shipmentType(com.bhagwat.scm.transportPlanner.enums.ShipmentType.ORDER_TO_STORE)
                        .transportMode(TransportMode.ROAD)
                        .loadType(group.size() > 3
                                ? com.bhagwat.scm.transportPlanner.enums.LoadType.FTL
                                : com.bhagwat.scm.transportPlanner.enums.LoadType.LTL)
                        .rtsOrders(group)
                        .notes("Orchestrated: type=" + selectedType + " | " + typeSelection.getSelectionReason())
                        .build();

                TransportPlanResponse plan = planService.createPlan(req);
                plans.add(plan);

                log.info("Orchestrator created plan {} (type={}, orders={}, carrier={})",
                        plan.getPlanNumber(), selectedType, group.size(), carrierId);

            } catch (Exception e) {
                log.error("Orchestrator failed to create plan for group of {} orders: {}",
                        group.size(), e.getMessage());
            }
        }

        log.info("=== Orchestrator complete: {} plans created ===", plans.size());
        return plans;
    }
}
