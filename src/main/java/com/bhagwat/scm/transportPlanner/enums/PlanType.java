package com.bhagwat.scm.transportPlanner.enums;

/**
 * Plan 1 DIRECT                  – 1 Source  → 1 Destination          (costlier, fastest)
 * Plan 2 SOURCE_CONSOLIDATION    – N Sources → 1 Destination          (slightly cheaper, efficient delivery)
 * Plan 3 DESTINATION_CONSOLIDATION – 1 Source → N Destinations        (same cost/time, e-com multi-drop)
 * Plan 4 CROSSDOCK               – N Sources → Hub(s) → N Destinations (cheapest, slow, partner network)
 */
public enum PlanType {
    DIRECT,
    SOURCE_CONSOLIDATION,
    DESTINATION_CONSOLIDATION,
    CROSSDOCK
}
