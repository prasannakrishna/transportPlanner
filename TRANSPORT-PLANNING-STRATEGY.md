# Transport Planning Strategy — Enterprise Design

## 1. Two Modes of Carrier Engagement

### Mode 1: Contract-Based Planning (Scheduled/Proactive)

**Trigger**: Daily/periodic planning run based on existing `SELLER_LOGISTICS` contracts in contractManager.

**How it works**:
- Contract defines: pickup frequency (daily/weekly), pickup window, routes, carrier, rates, SLA
- System collects all READY shipping orders for the day matching contract terms
- Groups them by: same carrier + same origin + compatible destinations
- Auto-creates Transport Plans (consolidation where possible)
- No seller intervention needed — it's pre-agreed

**Example**: 
- Contract says "FastMove picks up from Seller-A every Mon/Wed/Fri at 10am, delivers to Store-X and Store-Y"
- On Monday, system finds 5 SOs from Seller-A going to Store-X (3) and Store-Y (2)
- Creates 1 Transport Plan (DESTINATION_CONSOLIDATION) with 5 plan orders, 2 consignments

### Mode 2: Demand-Based Booking (Reactive/Ad-hoc)

**Trigger**: Seller marks SO as READY, no matching contract exists or seller wants spot pricing.

**How it works**:
- Seller marks SO ready → CBR broadcast to carriers
- Carriers respond with quotes
- Seller picks carrier
- Transport Plan created for that specific shipment (or batched if multiple SOs selected)

---

## 2. Transport Plan Creation Logic

### Key Principle: A Transport Plan is NOT per Shipping Order

A Transport Plan groups **multiple shipping orders** that share:
- Same carrier
- Same pickup date/window
- Compatible origin-destination routes

### Grouping Rules (Enterprise TMS Pattern)

```
Input: N Ready-to-Ship orders for a given planning window

Step 1: Group by CARRIER (from contract or selected)
Step 2: Within carrier, group by ORIGIN (same pickup location)
Step 3: Within origin, determine plan type:
        - All going to SAME destination → DIRECT (if 1 SO) or SOURCE_CONSOLIDATION
        - Going to DIFFERENT destinations:
          - If same region/route → DESTINATION_CONSOLIDATION (multi-drop)
          - If different regions + hub available → CROSSDOCK
Step 4: Create Transport Plan with:
        - N TransportPlanOrders (one per SO line item)
        - Legs based on plan type
        - Consignments (for multi-destination plans)
```

### Visual:

```
5 Shipping Orders from Seller-A on same day:
  SO-1: Seller-A → Store-X (Austin)     ┐
  SO-2: Seller-A → Store-X (Austin)     ├─ Consignment-1 (Store-X)
  SO-3: Seller-A → Store-X (Austin)     ┘
  SO-4: Seller-A → Store-Y (Dallas)     ┐
  SO-5: Seller-A → Store-Y (Dallas)     ┘─ Consignment-2 (Store-Y)

Result: 1 Transport Plan (DESTINATION_CONSOLIDATION)
  - Origin: Seller-A warehouse
  - Carrier: FastMove (from contract)
  - Leg 1: Seller-A → Hub (first-mile pickup)
  - Leg 2: Hub → Store-X (delivery consignment-1)
  - Leg 3: Hub → Store-Y (delivery consignment-2)
  - 5 TransportPlanOrders (one per SO)
  - 2 Consignments
```

---

## 3. Planning Engine Design

```
┌─────────────────────────────────────────────────────────────────┐
│                    TRANSPORT PLANNING ENGINE                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐    ┌──────────────────┐                   │
│  │ Contract Planner │    │  Demand Planner  │                   │
│  │ (Scheduled Job)  │    │ (Event-Driven)   │                   │
│  └────────┬─────────┘    └────────┬─────────┘                   │
│           │                       │                              │
│           └───────────┬───────────┘                              │
│                       ▼                                          │
│           ┌───────────────────────┐                              │
│           │   Grouping Engine     │                              │
│           │                       │                              │
│           │ 1. Group by carrier   │                              │
│           │ 2. Group by origin    │                              │
│           │ 3. Group by dest      │                              │
│           │ 4. Select plan type   │                              │
│           └───────────┬───────────┘                              │
│                       ▼                                          │
│           ┌───────────────────────┐                              │
│           │   Plan Builder        │                              │
│           │                       │                              │
│           │ - Create legs         │                              │
│           │ - Create consignments │                              │
│           │ - Assign plan orders  │                              │
│           │ - Calculate totals    │                              │
│           └───────────┬───────────┘                              │
│                       ▼                                          │
│           ┌───────────────────────┐                              │
│           │   Route Optimizer     │                              │
│           │                       │                              │
│           │ - Sequence stops      │                              │
│           │ - Minimize distance   │                              │
│           │ - Respect time windows│                              │
│           └───────────────────────┘                              │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Configuration

```yaml
# application.properties for transportPlanner

# Planning mode: IMMEDIATE (1 SO = 1 plan) or BATCHED (groups orders)
transport.planning.mode=BATCHED

# Cron for scheduled planning runs (every hour at :00)
transport.planning.cron=0 0 * * * *
```

- **IMMEDIATE**: Every RTS event creates a DIRECT plan instantly. Use for demand-based/ad-hoc.
- **BATCHED**: RTS events are queued. Planning engine runs on cron, groups by carrier+origin, creates consolidated plans. Use for contract-based.

In production, you'd typically run BATCHED with planning windows (e.g., 6am, 12pm, 6pm) matching carrier pickup schedules from contracts.

---

## 5. Answer to Your Questions

### Q: Is a Transport Plan per Shipping Order?

**No.** A Transport Plan groups multiple shipping orders that share the same carrier, origin, and compatible route. One TP can have 1-N TransportPlanOrders.

### Q: 5 SOs from same seller to different destinations — 1 TP or 5?

**1 Transport Plan** (type: DESTINATION_CONSOLIDATION) with:
- 5 TransportPlanOrders (one per SO)
- N Consignments (one per unique destination)
- Legs: 1 first-mile (seller → hub/vehicle) + N last-mile (one per destination)

### Q: When does it become multiple TPs?

Multiple TPs are created when:
- Different carriers (each carrier gets its own plan)
- Different origins (can't consolidate if pickup locations differ)
- Incompatible time windows (one SO needs same-day, another is next-week)
- Weight/volume exceeds vehicle capacity (split into multiple loads)

---

## 6. Complete Flow Summary

```
┌─────────────────────────────────────────────────────────────────────────┐
│ MODE 1: CONTRACT-BASED (Proactive)                                       │
│                                                                          │
│ Contract says: "FastMove picks from Seller-A Mon/Wed/Fri"                │
│                                                                          │
│ Monday 6am: Planning Engine runs                                         │
│   → Finds 5 BOOKED RTS orders for FastMove from Seller-A                │
│   → Groups: 3 going to Store-X, 2 going to Store-Y                      │
│   → Creates 1 DESTINATION_CONSOLIDATION plan                             │
│   → Generates Transport Orders (1 per leg)                               │
│   → carrierService creates shipments                                     │
│   → FastMove dispatches vehicle at 10am (per contract)                   │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│ MODE 2: DEMAND-BASED (Reactive)                                          │
│                                                                          │
│ Seller marks SO as READY (no contract or wants spot pricing)             │
│                                                                          │
│ → CBR broadcast to carriers                                              │
│ → Carriers respond with quotes                                           │
│ → Seller picks carrier (sees contract terms vs quoted rates)             │
│ → RTS status = BOOKED                                                    │
│ → Kafka: rts.created                                                     │
│ → transportPlanner creates DIRECT plan immediately                       │
│ → Transport Order → Shipment → Execution                                 │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Entity Relationships (Updated)

```
Contract (contractManager)
  │
  ├── Defines: carrier, rates, SLA, pickup frequency, routes
  │
  ▼
Planning Engine (transportPlanner)
  │
  ├── Reads contracts to know which carrier serves which seller
  ├── Reads BOOKED RTS orders from carrierService
  ├── Groups by: carrier → origin → destination
  │
  ▼
Transport Plan
  ├── planType: DIRECT | SOURCE_CONSOLIDATION | DESTINATION_CONSOLIDATION | CROSSDOCK
  ├── carrierId (from contract or seller selection)
  ├── legs[] (route segments)
  ├── orders[] (1 per shipping order included)
  └── consignments[] (1 per unique destination, for multi-drop)
       │
       ▼
  Transport Order (1 per leg, sent to carrier for execution)
       │
       ▼
  Transport Shipment (physical tracking in carrierService)
```


---

## 8. Plan Type Selection Logic (from execution.txt)

```
Input: Set of BOOKED RTS orders for a carrier

┌─────────────────────────────────────────────────────────────────┐
│                                                                  │
│  Count unique ORIGINS and DESTINATIONS                           │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ 1 origin + 1 destination                                │    │
│  │ → DIRECT (Plan 1)                                       │    │
│  │   Costlier, fastest. Single vehicle, single route.      │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ N origins + 1 destination                               │    │
│  │ → SOURCE_CONSOLIDATION (Plan 2)                         │    │
│  │   Slightly cheaper. Pickup from multiple sellers,       │    │
│  │   deliver to single warehouse/store.                    │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ 1 origin + N destinations                               │    │
│  │ → DESTINATION_CONSOLIDATION (Plan 3)                    │    │
│  │   Same cost/time. One seller, multi-drop to             │    │
│  │   multiple stores/customers (e-com pattern).            │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ N origins + N destinations                              │    │
│  │ → CROSSDOCK (Plan 4)                                    │    │
│  │   Cheapest, slowest, complex.                           │    │
│  │   Requires: allowPartnerNetwork=true on all contracts   │    │
│  │                                                         │    │
│  │   Execution:                                            │    │
│  │   1. Group RTS by source → FIRST_LEG per source → Hub  │    │
│  │   2. At Hub: unload, sort, repackage by destination     │    │
│  │   3. Group items by destination → Consignments          │    │
│  │   4. SECOND_LEG per consignment → final destination     │    │
│  │   5. Hub must support cross-docking operations          │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Pre-requisites checked before plan creation:

| Plan Type | Pre-requisite |
|-----------|--------------|
| DIRECT | Carrier assigned (from contract or seller selection) |
| SOURCE_CONSOLIDATION | Same carrier serves all source sellers |
| DESTINATION_CONSOLIDATION | Carrier covers all destination routes |
| CROSSDOCK | `allowPartnerNetwork=true` on ALL seller contracts + Hub warehouse identified |

### What happens at the Cross-dock Hub:

1. Inbound vehicles arrive from multiple sellers (FIRST_LEG complete)
2. Goods unloaded and sorted by destination
3. Repackaged into outbound consignments (grouped by destination)
4. Loaded onto outbound vehicles (SECOND_LEG begins)
5. Each outbound vehicle carries 1 consignment to 1 destination

This is the standard **hub-and-spoke** model used by enterprise logistics (FedEx, DHL, BlueDart).
