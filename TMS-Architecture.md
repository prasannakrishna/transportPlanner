# Transportation Management System (TMS) — Technical Architecture Document

**Version**: 1.0  
**Date**: May 2026  
**Author**: SCM Platform Team  
**Services**: `transportPlanner`, `carrierService`

---

## 1. Executive Summary

The Transportation Management System (TMS) is a microservices-based logistics platform that handles the complete lifecycle of goods movement — from transport request creation through planning, carrier assignment, execution, real-time tracking, and delivery confirmation.

The system is split into two core services:
- **transportPlanner** — Planning, routing, consolidation, and orchestration
- **carrierService** — Carrier management, execution, tracking, and settlement

Communication between services is event-driven via Apache Kafka, ensuring loose coupling and resilience.

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         TRANSPORTATION MANAGEMENT SYSTEM                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────────────────┐     ┌─────────────────────────────────┐    │
│  │       transportPlanner          │     │         carrierService          │    │
│  │     (Planning & Orchestration)  │     │     (Execution & Tracking)      │    │
│  │                                 │     │                                 │    │
│  │  • Transport Plan CRUD          │     │  • Carrier Master               │    │
│  │  • Multi-leg routing            │     │  • Vehicle & Driver Mgmt        │    │
│  │  • Consolidation strategies     │     │  • Carrier Booking (CBR)        │    │
│  │  • Transport Order generation   │     │  • Transport Request            │    │
│  │  • Route templates              │     │  • Ready-to-Ship (RTS)          │    │
│  │  • Carrier availability         │     │  • Transport Shipment           │    │
│  │  • Consignment grouping         │     │  • Milestone Posting            │    │
│  │                                 │     │  • ASN Generation               │    │
│  │                                 │     │  • Exception Management         │    │
│  │                                 │     │  • Freight Invoicing            │    │
│  └────────────────┬────────────────┘     └────────────────┬────────────────┘    │
│                   │                                       │                      │
│                   └───────────────┬───────────────────────┘                      │
│                                   │                                              │
│                          ┌────────▼────────┐                                     │
│                          │   Apache Kafka   │                                     │
│                          │                  │                                     │
│                          │  Topics:         │                                     │
│                          │  • transport.rts.created                               │
│                          │  • transport.plan.created                              │
│                          │  • transport.order.created                             │
│                          │  • transport.shipment.created                          │
│                          │  • transport.shipment.milestone                        │
│                          │  • transport.shipment.delivered                        │
│                          │  • transport.booking.request.broadcast                 │
│                          │  • transport.asn.sent                                  │
│                          └─────────────────┘                                     │
│                                                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │                        Shared Infrastructure                             │    │
│  │  PostgreSQL (per-tenant schemas) │ Config Server │ Eureka │ API Gateway  │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Entity Relationship Model

### 3.1 Complete Entity Flow

```
TransportRequest ──creates──▶ ReadyToShipOrder ──triggers──▶ CarrierBookingRequest
                                     │                              │
                                     │                    broadcast to carriers
                                     │                              │
                                     │                    best response accepted
                                     │                              │
                                     ▼                              ▼
                              TransportPlan ◀──── carrier confirmed via CBR
                                     │
                          ┌──────────┼──────────┐
                          │          │          │
                          ▼          ▼          ▼
                    Plan Legs   Plan Orders  Consignments
                          │
                          ▼
                   TransportOrder (one per leg)
                          │
                          │ ──Kafka──▶ carrierService
                          │
                          ▼
                   TransportShipment (execution)
                          │
                          ├──▶ Milestones (tracking events)
                          │
                          ▼
                   AdvanceShipNotice (sent to consignee)
```


### 3.2 Entity Schemas & Relationships

#### TransportRequest (carrierService)

| Field | Type | Description |
|-------|------|-------------|
| `trId` | PK | Unique identifier |
| `trNumber` | String | Human-readable number |
| `cbrId` | FK → CBR | Linked booking request |
| `cbrRespId` | FK → CBR Response | Accepted carrier response |
| `carrierId` | FK → Carrier | Resolved carrier |
| `shippingOrderId` | String | Upstream purchase/shipping order |
| `shipmentType` | Enum | ORDER_TO_WAREHOUSE, ORDER_TO_STORE, ORDER_TO_CUSTOMER, INTER_WAREHOUSE, RETURN |
| `originAddress` | Embedded | Pickup location (locationId, street, city, state, pincode) |
| `destinationAddress` | Embedded | Delivery location |
| `cargoReadyDate` | Date | When goods are ready |
| `requestedPickupDate` | Date | Desired pickup |
| `requestedDeliveryDate` | Date | Desired delivery |
| `loadType` | Enum | FTL, LTL, PARCEL |
| `totalWeightKg` | Decimal | Total weight |
| `totalVolumeM3` | Decimal | Total volume |
| `totalPackages` | Integer | Package count |
| `contractId` | FK → Contract | Rate agreement reference |
| `status` | Enum | PENDING → APPROVED → RTS_CREATED → COMPLETED |

**Child**: `TransportRequestItem[]` — SKU, quantity, weight, PO reference

---

#### ReadyToShipOrder (carrierService)

| Field | Type | Description |
|-------|------|-------------|
| `rtsId` | PK | Unique identifier |
| `rtsNumber` | String | Human-readable number |
| `trId` | FK → TransportRequest | Parent request |
| `cbrId` | FK → CBR | Booking that fulfills this |
| `carrierId` | FK → Carrier | Assigned carrier |
| `shipper` | Embedded Party | Seller/warehouse sending goods |
| `consignee` | Embedded Party | Store/customer receiving goods |
| `originAddress` | Embedded | Pickup location |
| `destinationAddress` | Embedded | Delivery location |
| `cargoReadyDateTime` | DateTime | When goods are packed and ready |
| `cargoCutoffDateTime` | DateTime | Latest pickup time |
| `loadType` | Enum | FTL, LTL, PARCEL |
| `totalWeightKg` | Decimal | Total weight |
| `totalVolumeM3` | Decimal | Total volume |
| `totalPackages` | Integer | Package count |
| `asnSent` | Boolean | Whether ASN has been generated |
| `status` | Enum | DRAFT → READY → BOOKING_REQUESTED → BOOKED → SHIPPED → DELIVERED |

**Child**: `ReadyToShipItem[]` — SKU, orderedQty, packedQty, batchNo, destination per item

---

#### CarrierBookingRequest (carrierService)

| Field | Type | Description |
|-------|------|-------------|
| `cbrId` | PK | Unique identifier |
| `rtsId` | FK → RTS | Source RTS order |
| `carrierId` | FK → Carrier | Target carrier (or null for broadcast) |
| `shipmentType` | Enum | Type of movement |
| `origin/destination` | Embedded | Route |
| `status` | Enum | CREATED → BROADCAST → RESPONSES_RECEIVED → ACCEPTED → COMPLETED |

**Children**:
- `CarrierBookingBroadcast[]` — sent to multiple carriers
- `CarrierBookingResponse[]` — carrier quotes with rate, ETA, vehicle type

---

#### TransportPlan (transportPlanner)

| Field | Type | Description |
|-------|------|-------------|
| `planId` | PK | Unique identifier |
| `planNumber` | String | Human-readable (e.g., TP-001) |
| `planType` | Enum | DIRECT, SOURCE_CONSOLIDATION, DESTINATION_CONSOLIDATION, CROSSDOCK |
| `rtsId` | FK → RTS | Primary RTS (for DIRECT) |
| `rtsIds` | Text | Comma-separated RTS IDs (for consolidation) |
| `carrierId` | FK → Carrier | Assigned carrier |
| `cbrResponseId` | FK → CBR Response | Accepted booking response |
| `shipmentType` | Enum | Type of movement |
| `transportMode` | Enum | ROAD, RAIL, AIR, SEA, MULTIMODAL |
| `loadType` | Enum | FTL, LTL, PARCEL |
| `originLocation` | Embedded | Plan origin |
| `destinationLocation` | Embedded | Plan destination |
| `hubLocation` | Embedded | Cross-dock hub (CROSSDOCK only) |
| `plannedStartDateTime` | DateTime | Planned departure |
| `plannedEndDateTime` | DateTime | Planned arrival |
| `totalDistanceKm` | Decimal | Route distance |
| `totalWeightKg` | Decimal | Total cargo weight |
| `status` | Enum | DRAFT → PLANNED → ACTIVE → COMPLETED → CANCELLED |

**Children**:
- `TransportPlanLeg[]` — route segments
- `TransportPlanOrder[]` — line items from RTS
- `Consignment[]` — grouped items per destination


---

#### TransportPlanLeg (transportPlanner)

| Field | Type | Description |
|-------|------|-------------|
| `legId` | PK | Unique identifier |
| `planId` | FK → TransportPlan | Parent plan |
| `legSequence` | Integer | Order of execution (1, 2, 3...) |
| `legType` | Enum | FIRST_LEG, SECOND_LEG, LAST_MILE |
| `transportMode` | Enum | ROAD, RAIL, AIR, SEA |
| `carrierId` | FK → Carrier | Carrier for this leg |
| `vehicleId` | FK → Vehicle | Assigned vehicle |
| `vehicleNumber` | String | Registration number |
| `originLocation` | Embedded | Leg pickup point |
| `destinationLocation` | Embedded | Leg delivery point |
| `plannedPickupDateTime` | DateTime | Scheduled pickup |
| `plannedDeliveryDateTime` | DateTime | Scheduled delivery |
| `actualPickupDateTime` | DateTime | Actual pickup (updated by milestones) |
| `actualDeliveryDateTime` | DateTime | Actual delivery |
| `distanceKm` | Decimal | Leg distance |
| `status` | Enum | PENDING → IN_TRANSIT → COMPLETED → FAILED |

---

#### TransportOrder (transportPlanner) — NEW

| Field | Type | Description |
|-------|------|-------------|
| `toId` | PK | Unique identifier |
| `toNumber` | String | Human-readable (e.g., TO-001) |
| `planId` | FK → TransportPlan | Parent plan |
| `planNumber` | String | Plan reference |
| `legId` | FK → TransportPlanLeg | Which leg this TO executes |
| `carrierId` | FK → Carrier | Carrier to execute |
| `vehicleId` | FK → Vehicle | Assigned vehicle |
| `vehicleNumber` | String | Registration |
| `driverId` | FK → Driver | Assigned driver |
| `driverName` | String | Driver name |
| `shipmentType` | Enum | Type of movement |
| `transportMode` | Enum | Mode of transport |
| `originLocation` | Embedded | Pickup |
| `destinationLocation` | Embedded | Delivery |
| `plannedPickupDateTime` | DateTime | Scheduled pickup |
| `plannedDeliveryDateTime` | DateTime | Scheduled delivery |
| `totalWeightKg` | Decimal | Cargo weight |
| `freightCost` | Decimal | Agreed cost |
| `transportShipmentId` | FK → TransportShipment | Linked execution shipment (in carrierService) |
| `status` | Enum | CREATED → DISPATCHED_TO_CARRIER → CARRIER_ACCEPTED → VEHICLE_ASSIGNED → IN_EXECUTION → COMPLETED → FAILED |

**Child**: `TransportOrderItem[]` — rtsId, skuId, productName, quantity, weight

---

#### Consignment (transportPlanner)

| Field | Type | Description |
|-------|------|-------------|
| `consignmentId` | PK | Unique identifier |
| `consignmentNumber` | String | Human-readable (e.g., CON-001) |
| `planId` | FK → TransportPlan | Parent plan |
| `deliveryLegId` | FK → TransportPlanLeg | Which leg delivers this consignment |
| `destinationLocation` | Embedded | Final destination |
| `totalWeightKg` | Decimal | Consignment weight |
| `totalPackages` | Integer | Package count |
| `status` | Enum | CREATED → IN_TRANSIT_TO_HUB → AT_HUB → IN_TRANSIT_TO_DESTINATION → DELIVERED |

**Child**: `ConsignmentItem[]` — rtsId, sellerId, skuId, quantity, weight

---

#### TransportShipment (carrierService)

| Field | Type | Description |
|-------|------|-------------|
| `tsId` | PK | Unique identifier |
| `tsNumber` | String | Human-readable (e.g., TS-001) |
| `rtsId` | FK → RTS | Source RTS |
| `transportPlanId` | FK → TransportPlan | Parent plan |
| `transportPlanNumber` | String | Plan reference |
| `carrierId` | FK → Carrier | Executing carrier |
| `vehicleId` | FK → Vehicle | Assigned vehicle |
| `vehicleNumber` | String | Registration |
| `driverName` | String | Driver |
| `driverPhone` | String | Driver contact |
| `shipper` | Embedded Party | Sender |
| `consignee` | Embedded Party | Receiver |
| `originAddress` | Embedded | Pickup |
| `destinationAddress` | Embedded | Delivery |
| `actualPickupDateTime` | DateTime | When picked up |
| `estimatedDeliveryDateTime` | DateTime | ETA |
| `actualDeliveryDateTime` | DateTime | When delivered |
| `currentLocation` | String | Real-time GPS location |
| `status` | Enum | CREATED → PICKED → SHIPPED → IN_TRANSIT → REACHED_HUB → OUT_FOR_DELIVERY → DELIVERED / FAILED |

**Child**: `ShipmentMilestone[]` — tracking events (type, location, timestamp, postedBy)

---

#### AdvanceShipNotice / ASN (carrierService)

| Field | Type | Description |
|-------|------|-------------|
| `asnId` | PK | Unique identifier |
| `rtsId` | FK → RTS | Source RTS |
| `tsId` | FK → TransportShipment | Shipment that triggered ASN |
| `shipper` | Embedded Party | Sender |
| `consignee` | Embedded Party | Receiver |
| `shipmentStatus` | String | Current shipment status |
| `items[]` | List | What's arriving (SKU, qty, PO ref) |

**Purpose**: Sent to receiving party (store/warehouse) so they can prepare receiving docks, putaway tasks, and quality checks.

---

### 3.3 Plan Types Explained

```
┌─────────────────────────────────────────────────────────────────────────┐
│ PLAN TYPE 1: DIRECT                                                      │
│                                                                          │
│   Seller A ─────────────────────────────────────▶ Store X               │
│              (1 leg, 1 source, 1 destination)                            │
│                                                                          │
│   Use case: Single seller shipping to single store                       │
│   Cost: Highest (dedicated vehicle)                                      │
│   Speed: Fastest                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│ PLAN TYPE 2: SOURCE_CONSOLIDATION                                        │
│                                                                          │
│   Seller A ──┐                                                           │
│              ├──▶ Hub ──────────────────────────▶ Store X               │
│   Seller B ──┘   (collect from multiple sources)                         │
│                                                                          │
│   Legs: N first-legs (each seller → hub) + 1 second-leg (hub → store)   │
│   Use case: Multiple sellers in same region shipping to same store        │
│   Cost: Lower (shared last-mile)                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│ PLAN TYPE 3: DESTINATION_CONSOLIDATION                                   │
│                                                                          │
│                    ┌──▶ Store X                                           │
│   Seller A ───────┼──▶ Store Y                                           │
│                    └──▶ Store Z                                           │
│                                                                          │
│   Legs: 1 first-leg (seller → region) + N last-mile legs                 │
│   Use case: One seller supplying multiple stores (multi-drop)            │
│   Cost: Moderate                                                         │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│ PLAN TYPE 4: CROSSDOCK                                                   │
│                                                                          │
│   Seller A ──┐         ┌──▶ Store X                                      │
│              ├──▶ Hub ──┤                                                 │
│   Seller B ──┘         └──▶ Store Y                                      │
│                                                                          │
│   Legs: N first-legs (sellers → hub) + N second-legs (hub → stores)      │
│   Consignments: Items grouped by destination at hub                      │
│   Use case: Multiple sellers, multiple stores, partner carrier network   │
│   Cost: Cheapest (maximum consolidation)                                 │
│   Speed: Slowest (hub processing time)                                   │
└─────────────────────────────────────────────────────────────────────────┘
```


---

## 4. Event-Driven Integration (Kafka Topics)

### 4.1 Topic Catalog

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `transport.rts.created` | carrierService | transportPlanner | RTS details (rtsId, origin, dest, weight, items) |
| `transport.booking.request.broadcast` | carrierService | External carriers | CBR details for carrier bidding |
| `transport.plan.created` | transportPlanner | carrierService | Plan ID, RTS ID, plan number |
| `transport.plan.updated` | transportPlanner | carrierService | Plan status changes |
| `transport.order.created` | transportPlanner | carrierService | Full TO with origin/dest, carrier, items |
| `transport.shipment.created` | carrierService | transportPlanner | toId + tsId (links TO to shipment) |
| `transport.shipment.milestone` | carrierService | transportPlanner | tsId, milestone type, location |
| `transport.shipment.delivered` | carrierService | transportPlanner | tsId, rtsId, carrierId |
| `transport.asn.sent` | carrierService | storeService | ASN details for receiving preparation |

### 4.2 Event Sequence Diagram

```
Seller/Warehouse          carrierService              Kafka              transportPlanner
      │                        │                        │                        │
      │── Create TR ──────────▶│                        │                        │
      │                        │── Create RTS ─────────▶│                        │
      │                        │                        │── rts.created ────────▶│
      │                        │                        │                        │── Auto-create Plan
      │                        │◀── plan.created ───────│◀───────────────────────│
      │                        │                        │                        │
      │                        │── Broadcast CBR ──────▶│                        │
      │                        │◀── Carrier responds ───│                        │
      │                        │── Accept response ────▶│                        │
      │                        │                        │                        │
      │                        │                        │                        │── Activate Plan
      │                        │                        │                        │── Generate TOs
      │                        │                        │◀── order.created ──────│
      │                        │◀── order.created ──────│                        │
      │                        │                        │                        │
      │                        │── Create Shipment ────▶│                        │
      │                        │                        │── shipment.created ───▶│── Link TO↔Shipment
      │                        │                        │                        │
      │                        │── Assign Vehicle ──────│                        │
      │                        │── Post PICKED ────────▶│── milestone ──────────▶│── Update leg
      │                        │── Send ASN ───────────▶│                        │
      │                        │── Post IN_TRANSIT ────▶│── milestone ──────────▶│── Update leg
      │                        │── Post DELIVERED ─────▶│── delivered ──────────▶│── Complete leg
      │                        │                        │                        │── Complete plan
      │                        │                        │                        │
```

---

## 5. API Reference

### 5.1 transportPlanner APIs

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/transport/plans` | Create transport plan |
| GET | `/api/v1/transport/plans/{planId}` | Get plan details |
| GET | `/api/v1/transport/plans?carrierId=&status=` | List plans |
| POST | `/api/v1/transport/plans/{planId}/activate` | Activate plan (triggers TO generation) |
| POST | `/api/v1/transport/plans/{planId}/complete` | Mark plan completed |
| PATCH | `/api/v1/transport/plans/{planId}/legs/{legId}/status` | Update leg status |
| POST | `/api/v1/transport/orders/generate/{planId}` | Generate TOs from plan legs |
| GET | `/api/v1/transport/orders/{toId}` | Get transport order |
| GET | `/api/v1/transport/orders/plan/{planId}` | List TOs for a plan |
| GET | `/api/v1/transport/orders/carrier/{carrierId}` | List TOs for a carrier |
| GET | `/api/v1/transport/orders/carrier/{carrierId}/pending` | Pending TOs for carrier |
| PATCH | `/api/v1/transport/orders/{toId}/status` | Update TO status |
| PATCH | `/api/v1/transport/orders/{toId}/assign` | Assign vehicle + driver |
| PATCH | `/api/v1/transport/orders/{toId}/link-shipment` | Link TO to shipment |
| POST | `/api/v1/transport/route-templates` | Create route template |
| GET | `/api/v1/transport/route-templates` | List templates |
| POST | `/api/v1/transport/carrier-availability` | Register carrier availability |
| GET | `/api/v1/transport/carrier-availability/available` | Find available carriers |

### 5.2 carrierService APIs

| Method | Endpoint | Description |
|--------|----------|-------------|
| **Carrier Master** | | |
| POST | `/api/v1/carrier/carriers` | Register carrier |
| GET | `/api/v1/carrier/carriers` | List carriers |
| POST | `/api/v1/carrier/carriers/vehicles` | Add vehicle |
| PATCH | `/api/v1/carrier/carriers/vehicles/{id}/status` | Update vehicle status |
| **Transport Requests** | | |
| GET | `/api/v1/carrier/transport-requests/{trId}` | Get request |
| POST | `/api/v1/carrier/transport-requests/{trId}/items` | Add items |
| POST | `/api/v1/carrier/transport-requests/{trId}/create-rts` | Create RTS from request |
| **Ready-to-Ship** | | |
| GET | `/api/v1/carrier/rts/{rtsId}` | Get RTS |
| GET | `/api/v1/carrier/rts` | List RTS orders |
| POST | `/api/v1/carrier/rts/{rtsId}/approve` | Approve RTS |
| **Carrier Booking** | | |
| POST | `/api/v1/carrier/bookings` | Create booking request |
| POST | `/api/v1/carrier/bookings/{cbrId}/broadcast` | Broadcast to carriers |
| POST | `/api/v1/carrier/bookings/carrier-response` | Carrier submits response |
| POST | `/api/v1/carrier/bookings/{cbrId}/accept-response` | Accept best response |
| **Shipments & Tracking** | | |
| GET | `/api/v1/carrier/shipments/{tsId}` | Get shipment |
| GET | `/api/v1/carrier/shipments?carrierId=&status=` | List shipments |
| POST | `/api/v1/carrier/shipments/milestones` | Post milestone |
| PATCH | `/api/v1/carrier/shipments/{tsId}/assign-vehicle` | Assign vehicle |
| PATCH | `/api/v1/carrier/shipments/{tsId}/location` | Update GPS location |
| **ASN** | | |
| GET | `/api/v1/carrier/asn/{asnId}` | Get ASN |
| POST | `/api/v1/carrier/asn/{asnId}/acknowledge` | Consignee acknowledges |
| **Logistics Resolver** | | |
| GET | `/api/v1/logistics/resolve` | Find carriers for route |
| GET | `/api/v1/logistics/serviceability` | Check pincode serviceability |
| GET | `/api/v1/logistics/first-mile` | First-mile carriers |
| GET | `/api/v1/logistics/last-mile` | Last-mile carriers |
| **Maintenance** | | |
| CRUD | `/api/v1/carrier/drivers` | Driver management |
| CRUD | `/api/v1/carrier/fleets` | Fleet management |
| CRUD | `/api/v1/carrier/parties` | Source/destination parties |
| CRUD | `/api/v1/carrier/sites` | Warehouse/depot sites |
| CRUD | `/api/v1/carrier/operating-pincodes` | Serviceable pincodes |
| CRUD | `/api/v1/carrier/booking-pincodes` | Booking cutoff config |
| **Operations** | | |
| CRUD | `/api/v1/carrier/exceptions` | Transport exceptions |
| CRUD | `/api/v1/carrier/issues` | Issue tracking |
| CRUD | `/api/v1/carrier/invoices` | Freight invoices |
| CRUD | `/api/v1/carrier/payments` | Payment recording |
| CRUD | `/api/v1/carrier/integrations` | System integrations |
| **Configuration** | | |
| CRUD | `/api/v1/carrier/contracts` | Logistics contracts |
| CRUD | `/api/v1/carrier/rate-cards` | Rate cards per route |
| CRUD | `/api/v1/carrier/vehicle-config` | Vehicle type specs |
| CRUD | `/api/v1/carrier/notification-config` | Alert rules |
| CRUD | `/api/v1/carrier/user-groups` | Permission groups |
| **Dashboard** | | |
| GET | `/api/v1/carrier/dashboard/stats` | KPI aggregation |

---

## 6. Status Lifecycle

```
TransportRequest:   PENDING ──▶ APPROVED ──▶ RTS_CREATED ──▶ COMPLETED

ReadyToShipOrder:   DRAFT ──▶ READY ──▶ BOOKING_REQUESTED ──▶ BOOKED ──▶ SHIPPED ──▶ DELIVERED

CarrierBooking:     CREATED ──▶ BROADCAST ──▶ RESPONSES_RECEIVED ──▶ ACCEPTED ──▶ COMPLETED

TransportPlan:      DRAFT ──▶ PLANNED ──▶ ACTIVE ──▶ COMPLETED
                                              │            ▲
                                              └── CANCELLED

TransportPlanLeg:   PENDING ──▶ IN_TRANSIT ──▶ COMPLETED
                                      │
                                      └──▶ FAILED

TransportOrder:     CREATED ──▶ DISPATCHED_TO_CARRIER ──▶ CARRIER_ACCEPTED
                         ──▶ VEHICLE_ASSIGNED ──▶ IN_EXECUTION ──▶ COMPLETED
                                                         │
                                                         └──▶ FAILED

Consignment:        CREATED ──▶ IN_TRANSIT_TO_HUB ──▶ AT_HUB
                         ──▶ IN_TRANSIT_TO_DESTINATION ──▶ DELIVERED

TransportShipment:  CREATED ──▶ PICKED ──▶ SHIPPED ──▶ IN_TRANSIT
                         ──▶ REACHED_HUB ──▶ OUT_FOR_DELIVERY ──▶ DELIVERED
                                                         │
                                                    DELIVERY_FAILED ──▶ RETURNED

ASN:                CREATED ──▶ SENT ──▶ ACKNOWLEDGED
```

---

## 7. End-to-End Flow (Step by Step)

### Step 1: Transport Request Created
- **Who**: Seller/Warehouse/Store needs goods moved
- **Service**: carrierService
- **Action**: `POST /api/v1/carrier/transport-requests`
- **Result**: TransportRequest with items created (status: PENDING)

### Step 2: Ready-to-Ship Order Created
- **Who**: Warehouse confirms goods are packed
- **Service**: carrierService
- **Action**: `POST /api/v1/carrier/transport-requests/{trId}/create-rts`
- **Result**: RTS created, Kafka event `transport.rts.created` published

### Step 3: Carrier Booking
- **Who**: System or logistics coordinator
- **Service**: carrierService
- **Action**: `POST /api/v1/carrier/bookings` → `POST /{cbrId}/broadcast`
- **Result**: CBR broadcast to eligible carriers, responses collected

### Step 4: Carrier Selection
- **Who**: Logistics coordinator
- **Service**: carrierService
- **Action**: `POST /api/v1/carrier/bookings/{cbrId}/accept-response`
- **Result**: Best carrier confirmed, RTS updated with carrierId

### Step 5: Transport Plan Created
- **Who**: Auto (via Kafka) or manual
- **Service**: transportPlanner
- **Action**: `POST /api/v1/transport/plans` (or auto from `transport.rts.created`)
- **Result**: Plan with legs, orders, consignments created (status: DRAFT → PLANNED)

### Step 6: Plan Activated & Transport Orders Generated
- **Who**: Logistics coordinator
- **Service**: transportPlanner
- **Action**: `POST /api/v1/transport/plans/{planId}/activate` → auto-calls `generate`
- **Result**: TransportOrders created (one per leg), Kafka `transport.order.created` published

### Step 7: Shipment Created for Execution
- **Who**: Auto (Kafka consumer)
- **Service**: carrierService
- **Action**: Consumes `transport.order.created`, creates TransportShipment
- **Result**: Shipment ready for execution, `transport.shipment.created` published back

### Step 8: Vehicle & Driver Assignment
- **Who**: Fleet manager / dispatcher
- **Service**: carrierService
- **Action**: `PATCH /api/v1/carrier/shipments/{tsId}/assign-vehicle`
- **Result**: Vehicle and driver assigned to shipment

### Step 9: Milestone Posting (Execution)
- **Who**: Driver / system (GPS)
- **Service**: carrierService
- **Action**: `POST /api/v1/carrier/shipments/milestones`
- **Milestones**: PICKED → LOADED → DEPARTED_ORIGIN → IN_TRANSIT → REACHED_HUB → OUT_FOR_DELIVERY → DELIVERED
- **Result**: Shipment status advances, Kafka events published

### Step 10: ASN Sent
- **Who**: Auto (on PICKED milestone)
- **Service**: carrierService
- **Action**: ASN generated and sent to consignee
- **Result**: Receiving party prepared for incoming goods

### Step 11: Delivery Confirmation
- **Who**: Driver posts DELIVERED milestone
- **Service**: carrierService
- **Action**: Kafka `transport.shipment.delivered` published
- **Result**: transportPlanner marks leg COMPLETED, checks if all legs done → plan COMPLETED

---

## 8. Cross-Reference Table (Foreign Keys)

| From Entity | Field | To Entity | Field | Service |
|-------------|-------|-----------|-------|---------|
| ReadyToShipOrder | `trId` | TransportRequest | `trId` | carrierService |
| ReadyToShipOrder | `cbrId` | CarrierBookingRequest | `cbrId` | carrierService |
| CarrierBookingRequest | `rtsId` | ReadyToShipOrder | `rtsId` | carrierService |
| TransportPlan | `rtsId/rtsIds` | ReadyToShipOrder | `rtsId` | cross-service |
| TransportPlan | `cbrResponseId` | CarrierBookingResponse | `responseId` | cross-service |
| TransportPlanLeg | `planId` | TransportPlan | `planId` | transportPlanner |
| TransportOrder | `planId` | TransportPlan | `planId` | transportPlanner |
| TransportOrder | `legId` | TransportPlanLeg | `legId` | transportPlanner |
| TransportOrder | `transportShipmentId` | TransportShipment | `tsId` | cross-service |
| Consignment | `planId` | TransportPlan | `planId` | transportPlanner |
| Consignment | `deliveryLegId` | TransportPlanLeg | `legId` | transportPlanner |
| TransportShipment | `rtsId` | ReadyToShipOrder | `rtsId` | cross-service |
| TransportShipment | `transportPlanId` | TransportPlan | `planId` | cross-service |
| ShipmentMilestone | `tsId` | TransportShipment | `tsId` | carrierService |
| AdvanceShipNotice | `rtsId` | ReadyToShipOrder | `rtsId` | carrierService |
| AdvanceShipNotice | `tsId` | TransportShipment | `tsId` | carrierService |

---

## 9. Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.5 |
| Database | PostgreSQL (multi-tenant per-schema) |
| Messaging | Apache Kafka |
| Service Discovery | Eureka |
| Config | Spring Cloud Config Server |
| API Gateway | Spring Cloud Gateway |
| Observability | OpenTelemetry → Tempo (traces), Prometheus (metrics), Loki (logs), Grafana |
| Build | Maven |
| Container | Docker (eclipse-temurin:17-jre) |

---

## 10. Deployment Topology

```
┌─────────────────────────────────────────────────────────┐
│                    API Gateway (:8080)                    │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  transportPlanner (:8095)    carrierService (:8084)      │
│  contractManager (:8089)     sellerService (:8086)       │
│  storeService (:8092)        storeManager (:8091)        │
│  inventoryService (:8083)    orderService (:8085)        │
│                                                          │
├─────────────────────────────────────────────────────────┤
│  Config Server (:8888)  │  Eureka (:8761)  │  Kafka     │
│  PostgreSQL (:5432)     │  Grafana (:3000)  │  Tempo     │
└─────────────────────────────────────────────────────────┘
```

---

*End of Document*
