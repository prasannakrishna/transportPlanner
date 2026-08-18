# SCM Platform — Business Scenarios & Solution Design

**Version**: 1.0 | **Date**: May 2026 | **Platform**: Commart SCM

---

## Table of Contents

1. Seller Onboarding & Product Publishing
2. Community Order Fulfillment (Store-based)
3. Seller → Store Replenishment (Contract-based)
4. Carrier Assignment — Demand-based (Ad-hoc)
5. Carrier Assignment — Contract-based (Scheduled)
6. Transport Planning — Direct Shipment
7. Transport Planning — Multi-drop Delivery
8. Transport Planning — Cross-dock (Hub & Spoke)
9. Store In-store Picking & Last-mile Delivery
10. Financial Settlement & Billing

---

## Scenario 1: Seller Onboarding & Product Publishing

### Business Context
A seller (e.g., GreenLeaf Farms) wants to sell Jaggery through the platform to multiple stores and communities.

### Data Points
- Seller: GreenLeaf Farms (SEL-001)
- Product: Jaggery 1kg (SKU: JAG-1KG)
- Target stores: City Store East, Suburb Store
- Contract: SC-001 with Store, CNT-001 with Carrier

### Flow

```
┌──────────────┐     ┌──────────────┐     ┌────────────────┐     ┌──────────────┐
│ userService  │     │sellerService │     │ contractMgr    │     │inventoryService│
│              │     │              │     │                │     │              │
│ Create Org   │     │              │     │                │     │              │
│ Create User  │────▶│ Create Seller│     │                │     │              │
│ Assign Role  │     │ Add Products │     │                │     │              │
│              │     │ Add SKUs     │────▶│ Create Contract│     │              │
│              │     │ Set Pricing  │     │ (SELLER_STORE) │     │              │
│              │     │              │     │ (SELLER_LOGISTICS)   │              │
│              │     │ Publish      │─────────────────────────▶│ Inventory     │
│              │     │ Inventory    │     │                │     │ Created       │
└──────────────┘     └──────────────┘     └────────────────┘     └──────────────┘
```

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Register org | userService | `POST /api/orgs` |
| Create user | userService | `POST /api/users` |
| Create product | sellerService | `POST /api/v1/seller/products` |
| Create SKU | sellerService | `POST /api/v1/seller/skus` |
| Create contract | contractManager | `POST /api/contracts` |
| Publish inventory | inventoryService | Kafka: `inventory.events` |

---

## Scenario 2: Community Order Fulfillment (Store-based)

### Business Context
Eco Warriors community (48 members) places a weekly order for Bamboo Toothbrush Sets. The store fulfills from its inventory via in-store picking.

### Data Points
- Community: Eco Warriors (COM-001), 48 members
- Store: City Store East (STR-001)
- Product: Bamboo Toothbrush Set (BAM-BRUS)
- Order: 24 units/week
- Fulfillment: IN_STORE picking

### Flow

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ orderService │     │ storeService │     │ storeService │     │ storeService │
│              │     │              │     │  (Picking)   │     │  (Billing)   │
│              │     │              │     │              │     │              │
│ Community    │     │ Check Store  │     │ Create       │     │ Generate     │
│ Order Created│────▶│ Inventory    │────▶│ Pick List    │────▶│ Invoice      │
│ 24 units     │     │ Available?   │     │ Assign Picker│     │ CI-001       │
│              │     │ Yes: 180 qty │     │ Confirm Pick │     │ ₹620         │
│              │     │              │     │ Deduct Stock │     │              │
└──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
```

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Create order | orderService | `POST /api/orders` |
| Check inventory | storeService | `GET /api/store-inventory?storeId=STR-001&skuId=BAM-BRUS` |
| Create pick list | storeService | `POST /api/picking` |
| Confirm pick | storeService | `PATCH /api/picking/{id}/confirm` |
| Generate bill | storeService | `POST /api/billing` |


---

## Scenario 3: Seller → Store Replenishment (Contract-based)

### Business Context
Store inventory for Herbal Tea drops below min level (100 units). Contract with HerbalGrow says auto-replenishment triggers at reorder point (130 units). Store needs 150 units replenished.

### Data Points
- Store: City Store East (STR-001)
- SKU: HRB-TEA, current stock: 95, min: 100, reorder: 130, max: 400
- Seller: HerbalGrow (SEL-003)
- Contract: SC-003 (min 100, max 400, valid until 2026-08-31)
- Replenishment qty: 150 units

### Flow

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ storeService │     │ storeManager │     │sellerService │     │ storeService │
│ (Inv Policy) │     │              │     │              │     │ (Receiving)  │
│              │     │              │     │              │     │              │
│ Stock < Min  │     │ Check        │     │ Create       │     │ GRN Created  │
│ Trigger      │────▶│ Contract     │────▶│ Shipping     │────▶│ Putaway Task │
│ Replenishment│     │ SC-003 Active│     │ Order        │     │ Stock Updated│
│ REP-001      │     │ Qty within   │     │ SO ready     │     │ 95→245 units │
│              │     │ max limit    │     │              │     │              │
└──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
```

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Check policy | storeService | `GET /api/inventory-policies/store/STR-001` |
| Validate contract | storeManager | `GET /api/store-contracts/store/STR-001` |
| Create replenishment | storeService | `POST /api/replenishments` |
| Create shipping order | sellerService | `POST /api/v1/seller/shipping-orders` |
| Receive goods (GRN) | storeService | `POST /api/receiving` |
| Putaway | storeService | `POST /api/putaway/confirm` |

---

## Scenario 4: Carrier Assignment — Demand-based (Ad-hoc)

### Business Context
GreenLeaf Farms has a shipping order for 200kg Jaggery going to City Store East. No pre-existing logistics contract. Seller wants to compare carrier quotes and pick the best one.

### Data Points
- Seller: GreenLeaf Farms (SEL-001)
- SO: 200 units Jaggery (200kg)
- Origin: Austin TX (seller warehouse)
- Destination: City Store East, New York
- Carriers responding: FastMove (₹4,500, 3 days), QuickShip (₹3,800, 5 days)

### Flow

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│sellerService │     │carrierService│     │  Carriers    │     │  Seller UI   │
│              │     │              │     │              │     │              │
│ Mark SO      │     │ Create TR    │     │              │     │              │
│ as READY     │────▶│ Create RTS   │     │              │     │              │
│              │     │ Broadcast CBR│────▶│ FastMove:    │     │              │
│              │     │ to carriers  │     │ ₹4,500/3days │     │              │
│              │     │              │◀────│ QuickShip:   │     │              │
│              │     │              │     │ ₹3,800/5days │     │              │
│              │     │              │     │              │     │ View Quotes  │
│              │     │              │◀────────────────────────│ Compare:     │
│              │     │              │     │              │     │ Rate vs SLA  │
│              │     │ Assign       │◀────────────────────────│ Select       │
│              │     │ FastMove     │     │              │     │ FastMove ✓   │
│              │     │ RTS=BOOKED   │     │              │     │              │
└──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
                              │
                              ▼ Kafka: rts.created
                     ┌──────────────────┐
                     │ transportPlanner │
                     │ Create DIRECT    │
                     │ plan → TO → Ship │
                     └──────────────────┘
```

### Seller Decision Factors (shown in UI)

| Factor | FastMove | QuickShip |
|--------|----------|-----------|
| Quoted Rate | ₹4,500 | ₹3,800 |
| ETA | 3 days | 5 days |
| SLA | 48h | 72h |
| Vehicle | Truck | Van |
| Has Contract? | ✅ CNT-001 | ❌ No |
| Contract Rate | ₹4,200 | — |
| Penalty/day | ₹500 | — |
| Insurance | ✅ | ❌ |

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Mark ready | sellerService | `POST /api/v1/seller/shipping-orders/{id}/mark-ready` |
| View quotes | carrierService | `GET /api/v1/carrier/seller-selection/responses/{rtsId}` |
| Select carrier | carrierService | `POST /api/v1/carrier/seller-selection/select` |
| Auto-create plan | transportPlanner | Kafka consumer → `autoCreatePlanFromRts()` |


---

## Scenario 5: Carrier Assignment — Contract-based (Scheduled)

### Business Context
FastMove Logistics has a contract with 3 sellers. Contract says: pickup Mon/Wed/Fri. On Monday, the planning engine collects all ready shipments and creates optimized plans.

### Data Points
- Carrier: FastMove Logistics (CAR-001)
- Contract: CNT-001 (pickup Mon/Wed/Fri, SLA 3 days, ₹2.10/km)
- Monday's ready orders:
  - RTS-001: GreenLeaf → Store-X (200kg)
  - RTS-002: BambooWorks → Store-X (50kg)
  - RTS-003: HerbalGrow → Store-Y (75kg)

### Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Monday 6:00 AM — Planning Engine Runs (Cron)                              │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  Step 1: Fetch BOOKED RTS orders for today                                │
│          → Found 3 orders for FastMove                                    │
│                                                                           │
│  Step 2: Group by carrier                                                 │
│          → FastMove: [RTS-001, RTS-002, RTS-003]                         │
│                                                                           │
│  Step 3: Group by origin                                                  │
│          → Austin: [RTS-001, RTS-002, RTS-003] (all from Austin area)    │
│                                                                           │
│  Step 4: Analyze destinations                                             │
│          → Store-X (2 orders), Store-Y (1 order) = 2 destinations        │
│          → Plan Type: DESTINATION_CONSOLIDATION                           │
│                                                                           │
│  Step 5: Create Transport Plan TP-2026-00012                              │
│          → 3 TransportPlanOrders (one per RTS)                            │
│          → 2 Consignments (Store-X: 250kg, Store-Y: 75kg)                │
│          → Leg 1: Pickup all from Austin area                             │
│          → Leg 2: Deliver to Store-X                                      │
│          → Leg 3: Deliver to Store-Y                                      │
│                                                                           │
│  Step 6: Generate Transport Orders → carrierService creates shipments     │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
```

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Trigger planning | transportPlanner | `POST /api/v1/transport/planning-engine/run` (or cron) |
| Fetch RTS | carrierService | `GET /api/v1/carrier/rts?status=BOOKED` |
| Create plan | transportPlanner | Internal `planService.createPlan()` |
| Generate TOs | transportPlanner | `POST /api/v1/transport/orders/generate/{planId}` |

---

## Scenario 6: Transport Planning — Direct Shipment (Plan 1)

### Business Context
Single seller, single destination. EcoSoaps ships 100 units of Organic Soap to Suburb Store. FastMove assigned.

### Data Points
- Plan Type: DIRECT
- RTS: RTS-004 (EcoSoaps → Suburb Store, 60kg)
- Carrier: FastMove
- Route: Dallas TX → Miami FL (1,300 km)
- Cost: ₹2.10/km × 1,300 = ₹2,730

### Diagram

```
  EcoSoaps (Dallas TX)                          Suburb Store (Miami FL)
       │                                              ▲
       │              LEG 1 (FIRST_LEG)               │
       └──────────────────────────────────────────────┘
         Vehicle: TX-1234 (Truck)
         Driver: Mike Johnson
         Distance: 1,300 km
         ETA: 2 days
```

### Transport Plan Structure
```json
{
  "planNumber": "TP-2026-00013",
  "planType": "DIRECT",
  "carrierId": "CAR-001",
  "totalWeightKg": 60,
  "legs": [
    { "legSequence": 1, "legType": "FIRST_LEG", "origin": "Dallas TX", "destination": "Miami FL" }
  ],
  "orders": [
    { "rtsId": "RTS-004", "skuId": "ECO-SOAP", "quantity": 100, "weightKg": 60 }
  ],
  "consignments": []
}
```

---

## Scenario 7: Transport Planning — Multi-drop Delivery (Plan 3)

### Business Context
GreenLeaf Farms ships Jaggery to 3 different stores from their single warehouse. One vehicle does multi-drop.

### Data Points
- Plan Type: DESTINATION_CONSOLIDATION
- Seller: GreenLeaf Farms (Austin TX)
- Destinations: Store-X (NY), Store-Y (Miami), Store-Z (Atlanta)
- Total: 500kg across 3 orders

### Diagram

```
                              ┌──── LEG 2 ────▶ Store-X (New York)
                              │                  Consignment-1: 200kg
  GreenLeaf Farms ── LEG 1 ──┤
  (Austin TX)        (Pickup) │
  500kg total                 ├──── LEG 3 ────▶ Store-Y (Miami)
                              │                  Consignment-2: 180kg
                              │
                              └──── LEG 4 ────▶ Store-Z (Atlanta)
                                                 Consignment-3: 120kg
```

### Transport Plan Structure
```json
{
  "planNumber": "TP-2026-00014",
  "planType": "DESTINATION_CONSOLIDATION",
  "legs": [
    { "seq": 1, "type": "FIRST_LEG", "origin": "Austin TX", "dest": "Route start" },
    { "seq": 2, "type": "LAST_MILE", "origin": "Route", "dest": "New York" },
    { "seq": 3, "type": "LAST_MILE", "origin": "Route", "dest": "Miami" },
    { "seq": 4, "type": "LAST_MILE", "origin": "Route", "dest": "Atlanta" }
  ],
  "consignments": [
    { "number": "CON-001", "destination": "Store-X", "weight": 200, "deliveryLeg": "leg-2" },
    { "number": "CON-002", "destination": "Store-Y", "weight": 180, "deliveryLeg": "leg-3" },
    { "number": "CON-003", "destination": "Store-Z", "weight": 120, "deliveryLeg": "leg-4" }
  ]
}
```


---

## Scenario 8: Transport Planning — Cross-dock / Hub & Spoke (Plan 4)

### Business Context
3 sellers ship to 3 different stores. Instead of 9 direct trips, goods consolidate at a hub warehouse, get sorted by destination, and dispatched in 3 outbound trips. Cheapest but slowest.

### Data Points
- Plan Type: CROSSDOCK
- Sellers: GreenLeaf (Austin), BambooWorks (LA), HerbalGrow (Dallas)
- Hub: Warehouse Hub 1 (Houston TX) — supports cross-docking
- Destinations: Store-X (NY), Store-Y (Miami), Store-Z (Atlanta)
- Prerequisite: All 3 sellers have `allowPartnerNetwork=true` in their logistics contracts

### Diagram

```
  PHASE 1: First-mile (Sources → Hub)          PHASE 2: Last-mile (Hub → Destinations)

  GreenLeaf ──── LEG 1 ────┐                   ┌──── LEG 4 ────▶ Store-X (NY)
  (Austin)                  │                   │                  CON-001: Jaggery+Tea
                            │                   │
  BambooWorks ── LEG 2 ────┤──▶ Hub (Houston) ─┤──── LEG 5 ────▶ Store-Y (Miami)
  (LA)                      │    ┌──────────┐   │                  CON-002: Brush+Soap
                            │    │ Unload   │   │
  HerbalGrow ─── LEG 3 ────┘    │ Sort     │   └──── LEG 6 ────▶ Store-Z (Atlanta)
  (Dallas)                       │ Repackage│                      CON-003: Tea+Bottle
                                 │ by Dest  │
                                 └──────────┘
```

### Execution Steps (at Hub)
1. **Inbound**: 3 vehicles arrive from 3 sellers (LEG 1, 2, 3 complete)
2. **Unload**: All goods unloaded at receiving dock
3. **Sort**: Items sorted by final destination (which store needs what)
4. **Repackage**: Create 3 consignments (one per destination store)
5. **Load**: 3 outbound vehicles loaded with destination-specific consignments
6. **Dispatch**: LEG 4, 5, 6 begin — each vehicle goes to one store

### Cost Comparison
| Approach | Trips | Total km | Cost |
|----------|-------|----------|------|
| 9 Direct (each seller → each store) | 9 | ~15,000 km | ₹31,500 |
| Cross-dock (3 inbound + 3 outbound) | 6 | ~8,000 km | ₹16,800 |
| **Savings** | **33% fewer trips** | **47% less distance** | **₹14,700 saved** |

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Verify contracts | contractManager | `GET /api/contracts/search?type=SELLER_LOGISTICS` |
| Create plan | transportPlanner | `POST /api/v1/transport/plans` (planType=CROSSDOCK) |
| Hub operations | wmsService | Receiving → Sorting → Shipping |
| Outbound dispatch | carrierService | Transport Orders for LEG 4,5,6 |

---

## Scenario 9: Store In-store Picking & Last-mile Delivery

### Business Context
Austin Foodies community orders 95 units across 3 products. Store fulfills via picking, then dispatches last-mile delivery to community hub.

### Data Points
- Community: Austin Foodies (COM-004), 91 members
- Store: City Store East
- Order: Jaggery ×40, Herbal Tea ×30, Water Bottle ×25
- Fulfillment: LAST_MILE (delivery to community)
- Delivery Partner: Last Mile Pro (SLA: 2h, ₹3/order)

### Flow

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ orderService│    │storeService │    │storeService │    │storeService │    │ Delivery    │
│             │    │ (Inventory) │    │ (Picking)   │    │ (Billing)   │    │ Partner     │
│             │    │             │    │             │    │             │    │             │
│ Community   │    │ Reserve     │    │ Pick List   │    │ Invoice     │    │ Pickup from │
│ Order       │───▶│ 95 units    │───▶│ 3 lines     │───▶│ ₹1,140     │───▶│ store dock  │
│ Created     │    │ from bins   │    │ Picker: Bob │    │ Generated   │    │ Deliver to  │
│             │    │ A-01, A-02  │    │ Confirmed   │    │             │    │ community   │
│             │    │ B-03        │    │ Stock -95   │    │             │    │ hub         │
└─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘
```

### Pick List Detail
| Location | SKU | Product | Requested | Picked |
|----------|-----|---------|-----------|--------|
| A-01-03 | JAG-1KG | Jaggery 1kg | 40 | 40 |
| A-02-01 | HRB-TEA | Herbal Tea | 30 | 30 |
| B-03-02 | RWB-1L | Water Bottle | 25 | 25 |

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Create pick list | storeService | `POST /api/picking` (fulfillmentType=LAST_MILE) |
| Confirm pick | storeService | `PATCH /api/picking/{id}/confirm` |
| Generate invoice | storeService | `POST /api/billing` |
| Delivery invoice | storeService | `POST /api/delivery-invoices` |

---

## Scenario 10: Financial Settlement & Billing

### Business Context
End of month: Store settles with sellers (for goods sold), delivery partners (for deliveries made), and collects from communities (for orders fulfilled).

### Data Points (March 2026)

| Party | Type | Amount | Status |
|-------|------|--------|--------|
| Eco Warriors (community) | Revenue | ₹620 | Paid |
| Wellness Circle (community) | Revenue | ₹890 | Paid |
| Austin Foodies (community) | Revenue | ₹1,140 | Pending |
| FastMove Logistics (delivery) | Expense | ₹288 | Paid |
| Last Mile Pro (delivery) | Expense | ₹123 | Pending |
| GreenLeaf Farms (seller COGS) | Expense | ₹16,864 | Settled |

### P&L Summary
```
Revenue (Community Orders):     ₹90,300
- COGS (Seller Payments):      ₹63,775
= Gross Profit:                 ₹26,525
- Delivery Costs:               ₹ 2,340
- Store Operations:             ₹ 8,200
= Net Profit:                   ₹15,985  (17.7% margin)
```

### Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                    Monthly Settlement Cycle                        │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Community Invoices (Revenue)                                     │
│  ├── CI-001: Eco Warriors — ₹620 (PAID)                         │
│  ├── CI-002: Wellness Circle — ₹890 (PAID)                      │
│  └── CI-003: Austin Foodies — ₹1,140 (PENDING)                  │
│                                                                   │
│  Delivery Invoices (Expense)                                      │
│  ├── DI-001: FastMove — 64 orders — ₹288 (PAID)                 │
│  └── DI-003: Last Mile Pro — 41 orders — ₹123 (PENDING)         │
│                                                                   │
│  Seller Settlements (COGS)                                        │
│  ├── Based on goods sold from store inventory                     │
│  └── Reconciled against seller contracts (commission %)           │
│                                                                   │
│  P&L Report: GET /api/pl-report/store/{storeId}                  │
└──────────────────────────────────────────────────────────────────┘
```

### APIs Involved
| Step | Service | API |
|------|---------|-----|
| Community invoices | storeService | `GET /api/billing/store/{storeId}` |
| Delivery invoices | storeService | `GET /api/delivery-invoices/store/{storeId}` |
| Mark paid | storeService | `PATCH /api/billing/{id}/paid` |
| P&L report | storeService | `GET /api/pl-report/store/{storeId}` |
| Freight invoices | carrierService | `GET /api/v1/carrier/invoices` |

---

## Platform Architecture (All Scenarios)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              API Gateway (Tyk)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ │
│  │  Auth   │ │  User   │ │ Seller  │ │  Store  │ │Inventory│ │  Order  │ │
│  │ Service │ │ Service │ │ Service │ │ Service │ │ Service │ │ Service │ │
│  │ :8097   │ │ :8087   │ │ :8086   │ │ :8092   │ │ :8083   │ │ :8085   │ │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘ │
│                                                                              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ │
│  │ Carrier │ │Transport│ │Contract │ │  Store  │ │  WMS    │ │Customer │ │
│  │ Service │ │ Planner │ │ Manager │ │ Manager │ │ Service │ │ Service │ │
│  │ :8084   │ │ :8095   │ │ :8089   │ │ :8091   │ │ :8090   │ │ :8088   │ │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘ │
│                                                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│  Kafka │ PostgreSQL │ MongoDB │ Redis │ Keycloak │ Grafana/Prometheus/Loki  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

*End of Document*
