package com.bhagwat.scm.transportPlanner.controller;

import com.bhagwat.scm.transportPlanner.service.ContractBasedPlanningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Manual controls for the transport planning engine.
 * The engine also runs on schedule (cron), but these endpoints allow:
 * - Manual trigger of a planning run
 * - Status check
 */
@RestController
@RequestMapping("/api/v1/transport/planning-engine")
@RequiredArgsConstructor
public class PlanningEngineController {

    private final ContractBasedPlanningService planningService;

    /**
     * Manually trigger a planning run.
     * Collects all unplanned BOOKED RTS orders, groups them, creates plans.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerRun() {
        int plansCreated = planningService.triggerPlanningRun();
        return ResponseEntity.ok(Map.of(
                "status", "COMPLETED",
                "plansCreated", plansCreated,
                "message", plansCreated > 0
                        ? plansCreated + " transport plan(s) created"
                        : "No unplanned orders found"
        ));
    }
}
