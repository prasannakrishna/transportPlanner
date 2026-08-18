package com.bhagwat.scm.transportPlanner.controller;

import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.enums.TransportPlanStatus;
import com.bhagwat.scm.transportPlanner.service.PlanAmendmentService;
import com.bhagwat.scm.transportPlanner.service.TransportPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transport/plans")
@RequiredArgsConstructor
@Tag(name = "Transport Plan", description = "4 transport planning strategies: DIRECT, SOURCE_CONSOLIDATION, DESTINATION_CONSOLIDATION, CROSSDOCK")
public class TransportPlanController {

    private final TransportPlanService planService;
    private final PlanAmendmentService amendmentService;

    /**
     * Create a transport plan using one of the 4 strategies:
     *
     * PLAN 1 – DIRECT: 1 RTS, 1 source, 1 destination (costlier, fastest).
     * PLAN 2 – SOURCE_CONSOLIDATION: N RTSes from different sellers all going to 1 destination.
     * PLAN 3 – DESTINATION_CONSOLIDATION: 1 RTS (e-com), multiple customer destinations (last-mile multi-drop).
     * PLAN 4 – CROSSDOCK: N sources → cross-dock hub → N destinations. Requires seller contract allowPartnerNetwork=true.
     */
    @PostMapping
    @Operation(summary = "Create transport plan — specify planType to select strategy")
    public ResponseEntity<TransportPlanResponse> createPlan(@Valid @RequestBody CreateTransportPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planService.createPlan(request));
    }

    @GetMapping("/{planId}")
    @Operation(summary = "Get transport plan by ID (includes legs, orders, consignments)")
    public ResponseEntity<TransportPlanResponse> getPlan(@PathVariable String planId) {
        return ResponseEntity.ok(planService.getPlan(planId));
    }

    @GetMapping
    @Operation(summary = "List transport plans — filter by carrierId and/or status")
    public ResponseEntity<List<TransportPlanResponse>> listPlans(
            @RequestParam(required = false) String carrierId,
            @RequestParam(required = false) TransportPlanStatus status) {
        return ResponseEntity.ok(planService.listPlans(carrierId, status));
    }

    @PostMapping("/{planId}/activate")
    @Operation(summary = "Activate plan — PLANNED → ACTIVE (triggers carrier operations)")
    public ResponseEntity<TransportPlanResponse> activatePlan(@PathVariable String planId) {
        return ResponseEntity.ok(planService.activatePlan(planId));
    }

    @PostMapping("/{planId}/complete")
    @Operation(summary = "Mark plan as COMPLETED")
    public ResponseEntity<TransportPlanResponse> completePlan(@PathVariable String planId) {
        return ResponseEntity.ok(planService.completePlan(planId));
    }

    @PatchMapping("/{planId}/legs/{legId}/status")
    @Operation(summary = "Update leg status and actual timestamps")
    public ResponseEntity<TransportPlanResponse> updateLegStatus(
            @PathVariable String planId,
            @PathVariable String legId,
            @RequestBody UpdateLegStatusRequest request) {
        return ResponseEntity.ok(planService.updateLegStatus(planId, legId, request));
    }

    /**
     * Add an RTS order to an existing DRAFT/PLANNED plan (consolidation).
     * Recalculates cost pro-rata by weight. Notifies sellers if plan is PLANNED.
     * Returns 400 if plan is ACTIVE (frozen).
     */
    @PostMapping("/{planId}/add-order")
    @Operation(summary = "Add RTS to existing plan (consolidation). Recalculates cost.")
    public ResponseEntity<PlanAmendmentService.AmendmentResult> addOrderToPlan(
            @PathVariable String planId,
            @RequestBody AddOrderRequest request) {
        com.bhagwat.scm.transportPlanner.entity.TransportPlanOrder order =
                com.bhagwat.scm.transportPlanner.entity.TransportPlanOrder.builder()
                        .planId(planId)
                        .rtsItemId(request.rtsId)
                        .orderNumber(request.sellerId)
                        .productName(request.productName)
                        .skuId(request.skuId)
                        .quantity(request.quantity)
                        .weightKg(request.weightKg)
                        .build();
        PlanAmendmentService.AmendmentResult result = amendmentService.addRtsToPlan(planId, order, request.weightKg);
        return result.isAdded() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    /**
     * Get cost allocation breakdown for all sellers in a plan.
     */
    @GetMapping("/{planId}/cost-allocation")
    @Operation(summary = "View cost distribution among sellers in this plan")
    public ResponseEntity<PlanAmendmentService.AmendmentResult> getCostAllocation(@PathVariable String planId) {
        com.bhagwat.scm.transportPlanner.entity.TransportPlanOrder dummyOrder =
                com.bhagwat.scm.transportPlanner.entity.TransportPlanOrder.builder().build();
        // Use amendment service to just calculate (won't add since weight=0)
        PlanAmendmentService.AmendmentResult result = amendmentService.addRtsToPlan(planId, dummyOrder, java.math.BigDecimal.ZERO);
        return ResponseEntity.ok(result);
    }

    @lombok.Data
    public static class AddOrderRequest {
        private String rtsId;
        private String sellerId;
        private String skuId;
        private String productName;
        private java.math.BigDecimal quantity;
        private java.math.BigDecimal weightKg;
    }
}
