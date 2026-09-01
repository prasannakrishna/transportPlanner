package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.client.ContractManagerClient;
import com.bhagwat.scm.transportPlanner.client.CarrierNetworkClient;
import com.bhagwat.scm.transportPlanner.client.LogisticsCapabilityClient;
import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.entity.*;
import com.bhagwat.scm.transportPlanner.enums.*;
import com.bhagwat.scm.transportPlanner.kafka.TransportPlanKafkaProducer;
import com.bhagwat.scm.transportPlanner.orchestrator.CapacitySplitter;
import com.bhagwat.scm.transportPlanner.orchestrator.PlanTypeCostEstimator;
import com.bhagwat.scm.transportPlanner.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransportPlanService {

    private final TransportPlanRepository planRepository;
    private final ContractManagerClient contractManagerClient;
    private final TransportPlanKafkaProducer kafkaProducer;
    private final DistanceCalculator distanceCalculator;
    private final CarrierNetworkClient carrierNetworkClient;
    private final LogisticsCapabilityClient logisticsCapabilityClient;
    private final CapacitySplitter capacitySplitter;
    private final PlanTypeCostEstimator costEstimator;
    private final TransportOrderRepository transportOrderRepository;
    private final TransportPlanLegRepository transportPlanLegRepository;

    // ── Plan number generation ────────────────────────────────────────────────

    private String generatePlanNumber() {
        int year = Year.now().getValue();
        long count = planRepository.count() + 1;
        return String.format("TP-%d-%05d", year, count);
    }

    private String generateConsignmentNumber(int seq) {
        int year = Year.now().getValue();
        return String.format("CON-%d-%04d", year, seq);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API — route to strategy
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates a transport plan using the specified strategy.
     * The planType in the request selects one of the 4 strategies.
     */
    @Transactional
    public TransportPlanResponse createPlan(CreateTransportPlanRequest req) {
        return switch (req.getPlanType()) {
            case DIRECT -> planDirect(req);
            case SOURCE_CONSOLIDATION -> planSourceConsolidation(req);
            case DESTINATION_CONSOLIDATION -> planDestinationConsolidation(req);
            case CROSSDOCK -> planCrossdock(req);
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLAN 1: DIRECT — 1 Source → 1 Destination (Costlier, Fastest)
    //
    //  Seller/WH ──[FIRST_LEG]──► Warehouse/Store/Customer
    //
    // Single RTS, no consolidation, direct carrier, one leg.
    // ═════════════════════════════════════════════════════════════════════════

    private TransportPlanResponse planDirect(CreateTransportPlanRequest req) {
        RtsInfo rts = req.getRtsOrders().get(0);

        TransportPlanLeg leg = buildLeg(1, LegType.FIRST_LEG,
                req.getCarrierId(), req.getCarrierName(),
                rts.getSourceLocation(), rts.getDestinationLocation(),
                req.getTransportMode());

        TransportPlan plan = buildBasePlan(req, rts.getRtsId(),
                rts.getSourceLocation(), rts.getDestinationLocation(), null)
                .planType(PlanType.DIRECT)
                .rtsIds(rts.getRtsId())
                .totalWeightKg(rts.getTotalWeightKg())
                .totalVolumeM3(rts.getTotalVolumeM3())
                .totalPackages(rts.getTotalPackages())
                .orders(buildOrders(rts.getItems(), rts.getRtsId()))
                .legs(new ArrayList<>(List.of(leg)))
                .build();

        return saveAndPublish(plan);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLAN 2: SOURCE_CONSOLIDATION — N Sources → 1 Destination (Slightly Cheaper)
    //
    //  Seller-1 ──[PICKUP_LEG_1]──┐
    //  Seller-2 ──[PICKUP_LEG_2]──┤──► Warehouse/Store (single destination)
    //  Seller-3 ──[PICKUP_LEG_3]──┘
    //
    // Multiple RTSes from different sellers/warehouses all going to ONE destination.
    // Carrier does multi-stop pickup run, then delivers to common destination.
    // Slightly more time (multiple pickups) but less expensive per unit.
    // ═════════════════════════════════════════════════════════════════════════

    private TransportPlanResponse planSourceConsolidation(CreateTransportPlanRequest req) {
        // All RTSes share the same destination
        PlanLocationDto commonDestination = req.getRtsOrders().get(0).getDestinationLocation();

        List<TransportPlanLeg> legs = new ArrayList<>();
        List<TransportPlanOrder> allOrders = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger(1);

        BigDecimal totalWt = BigDecimal.ZERO;
        BigDecimal totalVol = BigDecimal.ZERO;
        int totalPkgs = 0;

        for (RtsInfo rts : req.getRtsOrders()) {
            // One FIRST_LEG pickup per unique source
            legs.add(buildLeg(seq.getAndIncrement(), LegType.FIRST_LEG,
                    req.getCarrierId(), req.getCarrierName(),
                    rts.getSourceLocation(), commonDestination,
                    req.getTransportMode()));

            allOrders.addAll(buildOrders(rts.getItems(), rts.getRtsId()));
            if (rts.getTotalWeightKg() != null) totalWt = totalWt.add(rts.getTotalWeightKg());
            if (rts.getTotalVolumeM3() != null) totalVol = totalVol.add(rts.getTotalVolumeM3());
            if (rts.getTotalPackages() != null) totalPkgs += rts.getTotalPackages();
        }

        String primaryRtsId = req.getRtsOrders().get(0).getRtsId();
        String allRtsIds = req.getRtsOrders().stream().map(RtsInfo::getRtsId).collect(Collectors.joining(","));

        // Use the first source as the plan's "origin" for display; common destination as destination
        PlanLocationDto firstSource = req.getRtsOrders().get(0).getSourceLocation();

        TransportPlan plan = buildBasePlan(req, primaryRtsId, firstSource, commonDestination, null)
                .planType(PlanType.SOURCE_CONSOLIDATION)
                .rtsIds(allRtsIds)
                .totalWeightKg(totalWt)
                .totalVolumeM3(totalVol)
                .totalPackages(totalPkgs)
                .legs(legs)
                .orders(allOrders)
                .build();

        return saveAndPublish(plan);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLAN 3: DESTINATION_CONSOLIDATION — 1 Source → N Destinations (E-com Multi-Drop)
    //
    //  Warehouse ──[LAST_MILE_1]──► Customer-1
    //            ──[LAST_MILE_2]──► Customer-2
    //            ──[LAST_MILE_3]──► Customer-3
    //
    // Single RTS (one warehouse/seller), multiple customer delivery addresses.
    // Same vehicle does multiple drop-offs from a single source.
    // Used for e-commerce last-mile delivery runs.
    // ═════════════════════════════════════════════════════════════════════════

    private TransportPlanResponse planDestinationConsolidation(CreateTransportPlanRequest req) {
        RtsInfo rts = req.getRtsOrders().get(0);

        // Build all destination locations: primary + additional
        List<PlanLocationDto> allDestinations = new ArrayList<>();
        allDestinations.add(rts.getDestinationLocation());
        if (rts.getAdditionalDestinations() != null) {
            allDestinations.addAll(rts.getAdditionalDestinations());
        }

        List<TransportPlanLeg> legs = new ArrayList<>();
        List<Consignment> consignments = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger(1);
        AtomicInteger conSeq = new AtomicInteger(1);

        for (PlanLocationDto dest : allDestinations) {
            // One LAST_MILE leg per customer destination
            TransportPlanLeg leg = buildLeg(seq.getAndIncrement(), LegType.LAST_MILE,
                    req.getCarrierId(), req.getCarrierName(),
                    rts.getSourceLocation(), dest, req.getTransportMode());
            legs.add(leg);

            // Create a consignment to track what goes to each customer
            Consignment con = Consignment.builder()
                    .consignmentNumber(generateConsignmentNumber(conSeq.getAndIncrement()))
                    .destinationLocation(toLocation(dest))
                    .status(ConsignmentStatus.CREATED)
                    .items(new ArrayList<>()) // items distributed across consignments in real system
                    .build();
            consignments.add(con);
        }

        // Distribute items across consignments proportionally (simplified: all items in first consignment)
        if (!consignments.isEmpty() && rts.getItems() != null) {
            consignments.get(0).getItems().addAll(
                    buildConsignmentItems(rts.getItems(), rts.getRtsId(), rts.getSellerId()));
        }

        TransportPlan plan = buildBasePlan(req, rts.getRtsId(),
                rts.getSourceLocation(), rts.getDestinationLocation(), null)
                .planType(PlanType.DESTINATION_CONSOLIDATION)
                .rtsIds(rts.getRtsId())
                .totalWeightKg(rts.getTotalWeightKg())
                .totalVolumeM3(rts.getTotalVolumeM3())
                .totalPackages(rts.getTotalPackages())
                .legs(legs)
                .orders(buildOrders(rts.getItems(), rts.getRtsId()))
                .consignments(consignments)
                .build();

        return saveAndPublish(plan);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PLAN 4: CROSSDOCK — N Sources → Hub → N Destinations (Cheapest, Partner Network)
    //
    //  Seller-1 ──[FIRST_LEG_1]──┐                ┌──[SECOND_LEG_A]──► Store-A
    //  Seller-2 ──[FIRST_LEG_2]──┤──► Cross-dock ─┤──[SECOND_LEG_B]──► Store-B
    //  Seller-3 ──[FIRST_LEG_3]──┘     Hub (WH)   └──[SECOND_LEG_C]──► Store-C
    //
    // Prerequisites: ALL sellers must have allowPartnerNetwork=true in their logistics contract.
    // Steps:
    //  1. Verify partner network consent for each seller via contractManager
    //  2. Group RTSes by source location → one FIRST_LEG per unique source → Hub
    //  3. Group all items by destination location → create one Consignment per destination
    //  4. Create one SECOND_LEG per destination → Hub to destination
    //  5. Each consignment links to its delivery leg (SECOND_LEG)
    // The hub warehouse handles: unloading, sorting, repackaging, and loading to outbound vehicles.
    // ═════════════════════════════════════════════════════════════════════════

    private TransportPlanResponse planCrossdock(CreateTransportPlanRequest req) {
        if (req.getHubLocation() == null) {
            throw new IllegalArgumentException("CROSSDOCK plan requires a hubLocation (cross-docking warehouse)");
        }

        // ── Step 1: Verify partner network consent for all sellers ─────────────
        for (RtsInfo rts : req.getRtsOrders()) {
            boolean allowed = contractManagerClient.isPartnerNetworkAllowed(rts.getSellerContractId())
                    || contractManagerClient.isWarehouseLogisticsLeverageAllowed(rts.getSellerContractId());
            if (!allowed) {
                throw new IllegalStateException(
                        "Seller " + rts.getSellerId() + " (contractId=" + rts.getSellerContractId()
                        + ") has NOT consented to partner network logistics. "
                        + "Set logisticsTerms.allowPartnerNetwork=true in the contract before using CROSSDOCK plan.");
            }
        }

        // ── Step 2: Group RTSes by source location (deduplication by locationId) ─
        Map<String, List<RtsInfo>> bySource = req.getRtsOrders().stream()
                .collect(Collectors.groupingBy(rts ->
                        rts.getSourceLocation().getLocationId() != null
                                ? rts.getSourceLocation().getLocationId()
                                : rts.getSourceLocation().getPincode()));

        List<TransportPlanLeg> legs = new ArrayList<>();
        AtomicInteger legSeq = new AtomicInteger(1);

        // ── Step 2b: Resolve multi-carrier routes from carrier network ─────────
        // For each unique origin→hub and hub→destination, check if network provides
        // a different carrier than the primary plan carrier.
        Map<String, CarrierNetworkClient.NetworkRouteLeg> networkLegMap = new HashMap<>();
        for (RtsInfo rts : req.getRtsOrders()) {
            String originPin = rts.getSourceLocation() != null ? rts.getSourceLocation().getPincode() : null;
            String destPin = rts.getDestinationLocation() != null ? rts.getDestinationLocation().getPincode() : null;
            if (originPin != null && destPin != null) {
                carrierNetworkClient.resolveRoute(originPin, destPin).ifPresent(resolution -> {
                    for (CarrierNetworkClient.NetworkRouteLeg nLeg : resolution.getLegs()) {
                        // Key by from→to pincode for lookup during leg building
                        String key = nLeg.getFromPincode() + "→" + nLeg.getToPincode();
                        networkLegMap.putIfAbsent(key, nLeg);
                    }
                    log.info("Network route resolved for {}→{}: {} legs, multi-carrier={}",
                            originPin, destPin, resolution.getLegs().size(), resolution.isMultiCarrier());
                });
            }
        }

        // Phase-1 legs: each unique source → Hub (use network carrier if available)
        String hubPincode = req.getHubLocation() != null ? req.getHubLocation().getPincode() : null;
        for (Map.Entry<String, List<RtsInfo>> entry : bySource.entrySet()) {
            PlanLocationDto sourceLocation = entry.getValue().get(0).getSourceLocation();
            String srcPin = sourceLocation != null ? sourceLocation.getPincode() : null;

            // Check if network provides a specific carrier for this first-mile leg
            String legCarrierId = req.getCarrierId();
            String legCarrierName = req.getCarrierName();
            String lookupKey = srcPin + "→" + hubPincode;
            CarrierNetworkClient.NetworkRouteLeg networkLeg = networkLegMap.get(lookupKey);
            if (networkLeg != null && networkLeg.getCarrierId() != null) {
                legCarrierId = networkLeg.getCarrierId();
                legCarrierName = networkLeg.getCarrierName();
            }

            legs.add(buildLeg(legSeq.getAndIncrement(), LegType.FIRST_LEG,
                    legCarrierId, legCarrierName,
                    sourceLocation, req.getHubLocation(), req.getTransportMode()));
        }

        // ── Step 3: Group all items by destination → create Consignments ──────
        // Key: destinationLocation.locationId (or pincode as fallback)
        Map<String, List<Map.Entry<RtsInfo, RtsItemSummary>>> byDest = new LinkedHashMap<>();

        for (RtsInfo rts : req.getRtsOrders()) {
            String destKey = rts.getDestinationLocation().getLocationId() != null
                    ? rts.getDestinationLocation().getLocationId()
                    : rts.getDestinationLocation().getPincode();

            if (rts.getItems() != null) {
                for (RtsItemSummary item : rts.getItems()) {
                    byDest.computeIfAbsent(destKey, k -> new ArrayList<>())
                            .add(Map.entry(rts, item));
                }
            } else {
                // No items listed — still create a consignment for this RTS destination
                byDest.computeIfAbsent(destKey, k -> new ArrayList<>());
            }
        }

        // Destination location map (destKey → PlanLocationDto)
        Map<String, PlanLocationDto> destLocations = new LinkedHashMap<>();
        for (RtsInfo rts : req.getRtsOrders()) {
            String key = rts.getDestinationLocation().getLocationId() != null
                    ? rts.getDestinationLocation().getLocationId()
                    : rts.getDestinationLocation().getPincode();
            destLocations.putIfAbsent(key, rts.getDestinationLocation());
        }

        // ── Step 4: Create one SECOND_LEG and one Consignment per destination ─
        List<Consignment> consignments = new ArrayList<>();
        AtomicInteger conSeq = new AtomicInteger(1);

        for (Map.Entry<String, PlanLocationDto> destEntry : destLocations.entrySet()) {
            String destKey = destEntry.getKey();
            PlanLocationDto destLocation = destEntry.getValue();

            // Phase-2 leg: Hub → destination (use network carrier if available)
            String legCarrierId = req.getCarrierId();
            String legCarrierName = req.getCarrierName();
            String destPin = destLocation != null ? destLocation.getPincode() : null;
            String lookupKey2 = hubPincode + "→" + destPin;
            CarrierNetworkClient.NetworkRouteLeg networkLeg2 = networkLegMap.get(lookupKey2);
            if (networkLeg2 != null && networkLeg2.getCarrierId() != null) {
                legCarrierId = networkLeg2.getCarrierId();
                legCarrierName = networkLeg2.getCarrierName();
            }

            TransportPlanLeg deliveryLeg = buildLeg(legSeq.getAndIncrement(), LegType.SECOND_LEG,
                    legCarrierId, legCarrierName,
                    req.getHubLocation(), destLocation, req.getTransportMode());
            legs.add(deliveryLeg);

            // Build consignment items for this destination
            List<ConsignmentItem> conItems = new ArrayList<>();
            BigDecimal conWt = BigDecimal.ZERO;
            BigDecimal conVol = BigDecimal.ZERO;
            int conPkgs = 0;

            List<Map.Entry<RtsInfo, RtsItemSummary>> destItems = byDest.getOrDefault(destKey, List.of());
            for (Map.Entry<RtsInfo, RtsItemSummary> e : destItems) {
                RtsInfo rts = e.getKey();
                RtsItemSummary item = e.getValue();
                conItems.add(ConsignmentItem.builder()
                        .rtsId(rts.getRtsId())
                        .rtsItemId(item.getRtsItemId())
                        .sellerId(rts.getSellerId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .skuId(item.getSkuId())
                        .quantity(item.getQuantity())
                        .weightKg(item.getWeightKg())
                        .volumeM3(item.getVolumeM3())
                        .packages(item.getPackages())
                        .build());
                if (item.getWeightKg() != null) conWt = conWt.add(item.getWeightKg());
                if (item.getVolumeM3() != null) conVol = conVol.add(item.getVolumeM3());
                if (item.getPackages() != null) conPkgs += item.getPackages();
            }

            Consignment con = Consignment.builder()
                    .consignmentNumber(generateConsignmentNumber(conSeq.getAndIncrement()))
                    .destinationLocation(toLocation(destLocation))
                    .totalWeightKg(conWt)
                    .totalVolumeM3(conVol)
                    .totalPackages(conPkgs)
                    .status(ConsignmentStatus.CREATED)
                    .items(conItems)
                    .build();

            // Delivery leg ID will be set after plan save (planId assignment happens in savePlan)
            // We store a marker; post-save we link consignment ↔ leg by sequence
            consignments.add(con);
        }

        // Totals across all RTSes
        BigDecimal totalWt = req.getRtsOrders().stream()
                .map(r -> r.getTotalWeightKg() != null ? r.getTotalWeightKg() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalVol = req.getRtsOrders().stream()
                .map(r -> r.getTotalVolumeM3() != null ? r.getTotalVolumeM3() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalPkgs = req.getRtsOrders().stream()
                .mapToInt(r -> r.getTotalPackages() != null ? r.getTotalPackages() : 0).sum();

        String primaryRtsId = req.getRtsOrders().get(0).getRtsId();
        String allRtsIds = req.getRtsOrders().stream().map(RtsInfo::getRtsId).collect(Collectors.joining(","));
        PlanLocationDto firstSource = req.getRtsOrders().get(0).getSourceLocation();
        PlanLocationDto firstDest = req.getRtsOrders().get(0).getDestinationLocation();

        // Consolidate all items into plan orders
        List<TransportPlanOrder> allOrders = new ArrayList<>();
        for (RtsInfo rts : req.getRtsOrders()) {
            allOrders.addAll(buildOrders(rts.getItems(), rts.getRtsId()));
        }

        TransportPlan plan = buildBasePlan(req, primaryRtsId, firstSource, firstDest, req.getHubLocation())
                .planType(PlanType.CROSSDOCK)
                .rtsIds(allRtsIds)
                .hubLocation(toLocation(req.getHubLocation()))
                .totalWeightKg(totalWt)
                .totalVolumeM3(totalVol)
                .totalPackages(totalPkgs)
                .legs(legs)
                .orders(allOrders)
                .consignments(consignments)
                .build();

        TransportPlan saved = savePlan(plan);

        // ── Step 5: Link consignments to their delivery legs by index ─────────
        List<TransportPlanLeg> savedLegs = saved.getLegs();
        List<Consignment> savedConsignments = saved.getConsignments();
        // Phase-2 legs start after all phase-1 legs; bySource.size() = number of phase-1 legs
        int phase2Start = bySource.size();
        for (int i = 0; i < savedConsignments.size(); i++) {
            if (phase2Start + i < savedLegs.size()) {
                savedConsignments.get(i).setDeliveryLegId(savedLegs.get(phase2Start + i).getLegId());
            }
        }
        TransportPlan finalSaved = planRepository.save(saved);

        publishPlan(finalSaved);
        return toResponse(finalSaved);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Query methods
    // ═════════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public TransportPlanResponse getPlan(String planId) {
        return toResponse(findPlan(planId));
    }

    @Transactional(readOnly = true)
    public List<TransportPlanResponse> listPlans(String carrierId, TransportPlanStatus status) {
        List<TransportPlan> plans;
        if (carrierId != null && status != null) plans = planRepository.findByCarrierIdAndStatus(carrierId, status);
        else if (carrierId != null) plans = planRepository.findByCarrierId(carrierId);
        else if (status != null) plans = planRepository.findByStatus(status);
        else plans = planRepository.findAll();
        return plans.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public TransportPlanResponse activatePlan(String planId) {
        TransportPlan plan = findPlan(planId);

        // Validate carrier vehicle capacity before activation
        BigDecimal totalWeight = plan.getTotalWeightKg() != null ? plan.getTotalWeightKg() : BigDecimal.ZERO;
        BigDecimal totalVolume = plan.getTotalVolumeM3() != null ? plan.getTotalVolumeM3() : BigDecimal.ZERO;

        var vehicleAssignment = capacitySplitter.validateAndAssign(
                plan.getCarrierId(), plan.getTransportMode(),
                totalWeight, totalVolume,
                plan.getPlannedStartDateTime());

        if (vehicleAssignment.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot activate plan " + plan.getPlanNumber()
                    + ": no vehicle with sufficient capacity available for carrier "
                    + plan.getCarrierId() + " (required: " + totalWeight + "kg)");
        }

        // Assign vehicle + driver to first leg (or all legs for single-carrier plans)
        CapacitySplitter.VehicleAssignment assignment = vehicleAssignment.get();
        if (!plan.getLegs().isEmpty()) {
            TransportPlanLeg firstLeg = plan.getLegs().get(0);
            firstLeg.setVehicleId(assignment.getVehicleId());
            firstLeg.setVehicleNumber(assignment.getVehicleNumber());
            firstLeg.setDriverId(assignment.getDriverId());
            firstLeg.setDriverName(assignment.getDriverName());
            firstLeg.setDriverPhone(assignment.getDriverPhone());
        }

        plan.setStatus(TransportPlanStatus.ACTIVE);
        TransportPlan saved = planRepository.save(plan);
        kafkaProducer.publishPlanUpdated(planId, Map.of("planId", planId, "status", "ACTIVE",
                "vehicleId", assignment.getVehicleId() != null ? assignment.getVehicleId() : "",
                "driverId", assignment.getDriverId() != null ? assignment.getDriverId() : ""));
        return toResponse(saved);
    }

    @Transactional
    public TransportPlanResponse completePlan(String planId) {
        TransportPlan plan = findPlan(planId);
        plan.setStatus(TransportPlanStatus.COMPLETED);
        TransportPlan saved = planRepository.save(plan);
        kafkaProducer.publishPlanUpdated(planId, Map.of("planId", planId, "status", "COMPLETED"));
        return toResponse(saved);
    }

    @Transactional
    public TransportPlanResponse updateLegStatus(String planId, String legId, UpdateLegStatusRequest req) {
        TransportPlan plan = findPlan(planId);
        plan.getLegs().stream().filter(l -> l.getLegId().equals(legId)).findFirst().ifPresent(leg -> {
            leg.setStatus(req.getStatus());
            if (req.getActualPickupDateTime() != null) leg.setActualPickupDateTime(req.getActualPickupDateTime());
            if (req.getActualDeliveryDateTime() != null) leg.setActualDeliveryDateTime(req.getActualDeliveryDateTime());
        });
        return toResponse(planRepository.save(plan));
    }

    // ── Kafka-triggered auto-plan (simple DIRECT) ─────────────────────────────

    @Transactional
    @SuppressWarnings("unchecked")
    public void autoCreatePlanFromRts(String rtsId, String carrierId, String shipmentTypeStr, Map<String, Object> event) {
        try {
            ShipmentType shipmentType = shipmentTypeStr != null
                    ? ShipmentType.valueOf(shipmentTypeStr) : ShipmentType.ORDER_TO_WAREHOUSE;

            // Previously always PlanType.DIRECT regardless of cost — the real cost
            // comparison (PlanTypeCostEstimator) only ever ran in the hourly BATCHED
            // cron job, which in practice never finds work because this IMMEDIATE
            // path already handles every RTS synchronously as soon as it's created.
            // Populate the same fields ContractBasedPlanningService does so a single
            // shipment gets a real DIRECT-vs-CROSSDOCK comparison too.
            RtsInfo rtsInfo = RtsInfo.builder()
                    .rtsId(rtsId)
                    .rtsNumber((String) event.get("rtsNumber"))
                    .sellerId((String) event.get("sellerId"))
                    .sellerContractId((String) event.get("sellerContractId"))
                    .sourceLocation(PlanLocationDto.builder()
                            .locationId((String) event.get("orig_location_id"))
                            .city((String) event.get("orig_city"))
                            .state((String) event.get("orig_state"))
                            .pincode((String) event.get("orig_pincode"))
                            .locationType(LocationType.SELLER)
                            .build())
                    .destinationLocation(PlanLocationDto.builder()
                            .locationId((String) event.get("dest_location_id"))
                            .city((String) event.get("dest_city"))
                            .state((String) event.get("dest_state"))
                            .pincode((String) event.get("dest_pincode"))
                            .locationType(LocationType.STORE)
                            .build())
                    .totalWeightKg(toBigDecimalOrZero(event.get("totalWeightKg")))
                    .totalVolumeM3(toBigDecimalOrZero(event.get("totalVolumeM3")))
                    .totalPackages(event.get("totalPackages") instanceof Number n ? n.intValue() : 0)
                    .build();

            PlanTypeCostEstimator.PlanTypeSelection selection =
                    costEstimator.selectOptimalType(List.of(rtsInfo), carrierId, null);
            PlanType planType = selection.getSelectedType();

            CreateTransportPlanRequest req = CreateTransportPlanRequest.builder()
                    .planType(planType)
                    .carrierId(carrierId)
                    .carrierName((String) event.getOrDefault("carrierName", ""))
                    .shipmentType(shipmentType)
                    .transportMode(TransportMode.ROAD)
                    .loadType(LoadType.LTL)
                    .rtsOrders(List.of(rtsInfo))
                    .notes("Auto-planned (immediate) | typeSelection: " + selection.getSelectionReason())
                    .build();
            TransportPlanResponse plan = createPlan(req);

            // Store allocation scoring context in plan custom_data (if present in event)
            Map<String, Object> scoringContext = (Map<String, Object>) event.get("scoringContext");
            if (scoringContext != null && plan.getPlanId() != null) {
                planRepository.findById(plan.getPlanId()).ifPresent(p -> {
                    Map<String, Object> customData = p.getCustomData() != null
                            ? new HashMap<>(p.getCustomData()) : new HashMap<>();
                    customData.put("allocationScoringContext", scoringContext);

                    // Validate carrier vs scoring recommendation
                    String recommendedSource = (String) scoringContext.get("selectedSourceId");
                    if (recommendedSource != null) {
                        customData.put("carrierValidation", Map.of(
                                "assignedCarrier", carrierId,
                                "allocationRecommendedSource", recommendedSource,
                                "consistent", true  // simplified — real check would compare facilities
                        ));
                    }
                    p.setCustomData(customData);
                    planRepository.save(p);
                });
            }

            log.info("Auto-created {} plan {} for rtsId={} (reason: {}, scoring context: {})",
                    planType, plan.getPlanNumber(), rtsId, selection.getSelectionReason(),
                    scoringContext != null ? "present" : "absent");
        } catch (Exception e) {
            log.error("Failed to auto-create plan for rtsId={}: {}", rtsId, e.getMessage());
        }
    }

    private BigDecimal toBigDecimalOrZero(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(val.toString()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    @Transactional
    public void handleMilestoneEvent(String tsId, String milestone, Map<String, Object> event) {
        Optional<TransportOrder> toOpt = transportOrderRepository.findByTransportShipmentId(tsId);
        if (toOpt.isEmpty() || toOpt.get().getLegId() == null) {
            log.info("Milestone {} for tsId={} — no linked TransportOrder/leg found, nothing to update", milestone, tsId);
            return;
        }

        TransportOrder to = toOpt.get();
        LegStatus newStatus = resolveLegStatus(milestone);
        if (newStatus == null) {
            log.info("Milestone {} for tsId={} — no leg-status mapping, ignoring", milestone, tsId);
            return;
        }

        transportPlanLegRepository.findById(to.getLegId()).ifPresentOrElse(leg -> {
            leg.setStatus(newStatus);
            LocalDateTime now = LocalDateTime.now();
            if ("PICKED".equalsIgnoreCase(milestone)) {
                leg.setActualPickupDateTime(now);
            } else if ("DELIVERED".equalsIgnoreCase(milestone)) {
                leg.setActualDeliveryDateTime(now);
            }
            transportPlanLegRepository.save(leg);
            log.info("Milestone {} for tsId={} — leg {} status -> {}", milestone, tsId, leg.getLegId(), newStatus);
        }, () -> log.warn("Milestone {} for tsId={} — TransportOrder references missing legId={}",
                milestone, tsId, to.getLegId()));
    }

    private LegStatus resolveLegStatus(String milestone) {
        if (milestone == null) return null;
        return switch (milestone.toUpperCase()) {
            case "PICKED", "LOADED", "DEPARTED_ORIGIN", "IN_TRANSIT",
                 "REACHED_HUB", "DEPARTED_HUB", "OUT_FOR_DELIVERY" -> LegStatus.IN_TRANSIT;
            case "DELIVERED" -> LegStatus.COMPLETED;
            case "DELIVERY_FAILED", "RETURNED_TO_ORIGIN" -> LegStatus.FAILED;
            default -> null; // BOOKING_CONFIRMED / VEHICLE_ASSIGNED — leg stays PENDING
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Builders & helpers
    // ═════════════════════════════════════════════════════════════════════════

    private TransportPlan.TransportPlanBuilder buildBasePlan(CreateTransportPlanRequest req,
                                                              String rtsId,
                                                              PlanLocationDto origin,
                                                              PlanLocationDto destination,
                                                              PlanLocationDto hub) {
        return TransportPlan.builder()
                .planNumber(generatePlanNumber())
                .rtsId(rtsId)
                .carrierId(req.getCarrierId())
                .carrierName(req.getCarrierName())
                .cbrResponseId(req.getCbrResponseId())
                .shipmentType(req.getShipmentType())
                .transportMode(req.getTransportMode() != null ? req.getTransportMode() : TransportMode.ROAD)
                .loadType(req.getLoadType() != null ? req.getLoadType() : LoadType.LTL)
                .originLocation(toLocation(origin))
                .destinationLocation(toLocation(destination))
                .hubLocation(toLocation(hub))
                .plannedStartDateTime(req.getPlannedStartDateTime())
                .plannedEndDateTime(req.getPlannedEndDateTime())
                .notes(req.getNotes())
                .status(TransportPlanStatus.PLANNED);
    }

    private TransportPlanLeg buildLeg(int seq, LegType legType, String carrierId, String carrierName,
                                       PlanLocationDto origin, PlanLocationDto destination, TransportMode mode) {
        // Calculate distance and ETA from RouteTemplate or haversine fallback
        String originPin = origin != null ? origin.getPincode() : null;
        String destPin = destination != null ? destination.getPincode() : null;
        DistanceCalculator.RouteMetrics metrics = distanceCalculator.calculate(originPin, destPin);

        // Verify the assigned carrier actually covers this leg's pincode for
        // its role (pickup vs delivery) — the carrier picked at RTS-booking
        // time may only do first/mid-leg, not last-mile, or vice versa.
        String legCarrierId = carrierId;
        String legCarrierName = carrierName;
        LogisticsCapabilityClient.Role role = legType == LegType.FIRST_LEG
                ? LogisticsCapabilityClient.Role.PICKUP : LogisticsCapabilityClient.Role.DELIVERY;
        String checkPincode = role == LogisticsCapabilityClient.Role.PICKUP ? originPin : destPin;
        Optional<LogisticsCapabilityClient.Substitute> substitute =
                logisticsCapabilityClient.checkCapability(carrierId, checkPincode, role);
        if (substitute.isPresent()) {
            log.info("Leg {} ({}, pincode={}): carrier {} is not {}-capable, substituting {}",
                    seq, legType, checkPincode, carrierId, role, substitute.get().getCarrierId());
            legCarrierId = substitute.get().getCarrierId();
            legCarrierName = substitute.get().getCarrierName();
        }

        return TransportPlanLeg.builder()
                .legSequence(seq)
                .legType(legType)
                .carrierId(legCarrierId)
                .carrierName(legCarrierName)
                .transportMode(mode != null ? mode : TransportMode.ROAD)
                .originLocation(toLocation(origin))
                .destinationLocation(toLocation(destination))
                .distanceKm(metrics.getDistanceKm())
                .status(LegStatus.PENDING)
                .build();
    }

    private List<TransportPlanOrder> buildOrders(List<RtsItemSummary> items, String rtsId) {
        if (items == null) return new ArrayList<>();
        return items.stream().map(i -> TransportPlanOrder.builder()
                .rtsItemId(i.getRtsItemId())
                .productId(i.getProductId())
                .productName(i.getProductName())
                .skuId(i.getSkuId())
                .quantity(i.getQuantity())
                .weightKg(i.getWeightKg())
                .volumeM3(i.getVolumeM3())
                .status(PlanOrderStatus.PENDING)
                .build()).collect(Collectors.toList());
    }

    private List<ConsignmentItem> buildConsignmentItems(List<RtsItemSummary> items, String rtsId, String sellerId) {
        if (items == null) return new ArrayList<>();
        return items.stream().map(i -> ConsignmentItem.builder()
                .rtsId(rtsId)
                .rtsItemId(i.getRtsItemId())
                .sellerId(sellerId)
                .productId(i.getProductId())
                .productName(i.getProductName())
                .skuId(i.getSkuId())
                .quantity(i.getQuantity())
                .weightKg(i.getWeightKg())
                .volumeM3(i.getVolumeM3())
                .packages(i.getPackages())
                .build()).collect(Collectors.toList());
    }

    private TransportPlanResponse saveAndPublish(TransportPlan plan) {
        TransportPlan saved = savePlan(plan);
        publishPlan(saved);
        return toResponse(saved);
    }

    private TransportPlan savePlan(TransportPlan plan) {
        // Assign planId so children can reference it
        if (plan.getPlanId() == null) plan.setPlanId(UUID.randomUUID().toString());
        String planId = plan.getPlanId();
        plan.getLegs().forEach(l -> l.setPlanId(planId));
        plan.getOrders().forEach(o -> o.setPlanId(planId));
        plan.getConsignments().forEach(c -> {
            c.setPlanId(planId);
            c.getItems().forEach(i -> i.setConsignmentId(c.getConsignmentId() != null
                    ? c.getConsignmentId() : UUID.randomUUID().toString()));
        });

        // Compute totalDistanceKm from sum of all leg distances
        BigDecimal totalDist = plan.getLegs().stream()
                .map(l -> l.getDistanceKm() != null ? l.getDistanceKm() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDist.compareTo(BigDecimal.ZERO) > 0) {
            plan.setTotalDistanceKm(totalDist);
        }

        return planRepository.save(plan);
    }

    private void publishPlan(TransportPlan saved) {
        List<String> rtsIdList = saved.getRtsIds() != null
                ? Arrays.asList(saved.getRtsIds().split(",")) : List.of();
        kafkaProducer.publishPlanCreated(saved.getPlanId(), Map.of(
                "planId", saved.getPlanId(),
                "planNumber", saved.getPlanNumber(),
                "planType", saved.getPlanType().name(),
                "rtsId", saved.getRtsId() != null ? saved.getRtsId() : "",
                "rtsIds", rtsIdList,
                "carrierId", saved.getCarrierId() != null ? saved.getCarrierId() : "",
                "planNumber", saved.getPlanNumber()
        ));
    }

    private TransportPlan findPlan(String planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Transport plan not found: " + planId));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Mappers
    // ═════════════════════════════════════════════════════════════════════════

    private TransportPlanResponse toResponse(TransportPlan plan) {
        List<String> rtsIdList = plan.getRtsIds() != null
                ? Arrays.asList(plan.getRtsIds().split(",")) : List.of();
        return TransportPlanResponse.builder()
                .planId(plan.getPlanId())
                .planNumber(plan.getPlanNumber())
                .planType(plan.getPlanType())
                .rtsId(plan.getRtsId())
                .rtsIds(rtsIdList)
                .carrierId(plan.getCarrierId())
                .carrierName(plan.getCarrierName())
                .cbrResponseId(plan.getCbrResponseId())
                .shipmentType(plan.getShipmentType())
                .transportMode(plan.getTransportMode())
                .loadType(plan.getLoadType())
                .status(plan.getStatus())
                .originLocation(toLocationDto(plan.getOriginLocation()))
                .destinationLocation(toLocationDto(plan.getDestinationLocation()))
                .hubLocation(toLocationDto(plan.getHubLocation()))
                .plannedStartDateTime(plan.getPlannedStartDateTime())
                .plannedEndDateTime(plan.getPlannedEndDateTime())
                .actualStartDateTime(plan.getActualStartDateTime())
                .actualEndDateTime(plan.getActualEndDateTime())
                .totalDistanceKm(plan.getTotalDistanceKm())
                .totalWeightKg(plan.getTotalWeightKg())
                .totalVolumeM3(plan.getTotalVolumeM3())
                .totalPackages(plan.getTotalPackages())
                .notes(plan.getNotes())
                .legs(plan.getLegs() == null ? List.of()
                        : plan.getLegs().stream().map(this::toLegDto).collect(Collectors.toList()))
                .orders(plan.getOrders() == null ? List.of()
                        : plan.getOrders().stream().map(this::toOrderDto).collect(Collectors.toList()))
                .consignments(plan.getConsignments() == null ? List.of()
                        : plan.getConsignments().stream().map(this::toConsignmentDto).collect(Collectors.toList()))
                .createdAt(plan.getCreatedAt())
                .build();
    }

    private TransportPlanLegDto toLegDto(TransportPlanLeg leg) {
        return TransportPlanLegDto.builder()
                .legId(leg.getLegId()).planId(leg.getPlanId())
                .legSequence(leg.getLegSequence()).legType(leg.getLegType())
                .transportMode(leg.getTransportMode()).status(leg.getStatus())
                .carrierId(leg.getCarrierId()).carrierName(leg.getCarrierName())
                .vehicleId(leg.getVehicleId()).vehicleNumber(leg.getVehicleNumber())
                .driverId(leg.getDriverId()).driverName(leg.getDriverName()).driverPhone(leg.getDriverPhone())
                .originLocation(toLocationDto(leg.getOriginLocation()))
                .destinationLocation(toLocationDto(leg.getDestinationLocation()))
                .plannedPickupDateTime(leg.getPlannedPickupDateTime())
                .plannedDeliveryDateTime(leg.getPlannedDeliveryDateTime())
                .actualPickupDateTime(leg.getActualPickupDateTime())
                .actualDeliveryDateTime(leg.getActualDeliveryDateTime())
                .distanceKm(leg.getDistanceKm()).notes(leg.getNotes())
                .build();
    }

    private TransportPlanOrderDto toOrderDto(TransportPlanOrder o) {
        return TransportPlanOrderDto.builder()
                .planOrderId(o.getPlanOrderId()).planId(o.getPlanId())
                .rtsItemId(o.getRtsItemId()).orderNumber(o.getOrderNumber())
                .orderLineId(o.getOrderLineId()).productId(o.getProductId())
                .productName(o.getProductName()).skuId(o.getSkuId())
                .quantity(o.getQuantity()).weightKg(o.getWeightKg())
                .volumeM3(o.getVolumeM3()).status(o.getStatus())
                .build();
    }

    private ConsignmentDto toConsignmentDto(Consignment c) {
        return ConsignmentDto.builder()
                .consignmentId(c.getConsignmentId())
                .consignmentNumber(c.getConsignmentNumber())
                .planId(c.getPlanId())
                .deliveryLegId(c.getDeliveryLegId())
                .destinationLocation(toLocationDto(c.getDestinationLocation()))
                .totalWeightKg(c.getTotalWeightKg())
                .totalVolumeM3(c.getTotalVolumeM3())
                .totalPackages(c.getTotalPackages())
                .status(c.getStatus())
                .items(c.getItems() == null ? List.of()
                        : c.getItems().stream().map(this::toConsignmentItemDto).collect(Collectors.toList()))
                .createdAt(c.getCreatedAt())
                .build();
    }

    private ConsignmentItemDto toConsignmentItemDto(ConsignmentItem i) {
        return ConsignmentItemDto.builder()
                .itemId(i.getItemId()).consignmentId(i.getConsignmentId())
                .rtsId(i.getRtsId()).rtsItemId(i.getRtsItemId())
                .sellerId(i.getSellerId()).productId(i.getProductId())
                .productName(i.getProductName()).skuId(i.getSkuId())
                .quantity(i.getQuantity()).weightKg(i.getWeightKg())
                .volumeM3(i.getVolumeM3()).packages(i.getPackages())
                .build();
    }

    private PlanLocation toLocation(PlanLocationDto dto) {
        if (dto == null) return null;
        return PlanLocation.builder()
                .locationId(dto.getLocationId()).locationName(dto.getLocationName())
                .locationType(dto.getLocationType()).orgId(dto.getOrgId())
                .street(dto.getStreet()).city(dto.getCity())
                .state(dto.getState()).pincode(dto.getPincode())
                .country(dto.getCountry()).build();
    }

    private PlanLocationDto toLocationDto(PlanLocation loc) {
        if (loc == null) return null;
        return PlanLocationDto.builder()
                .locationId(loc.getLocationId()).locationName(loc.getLocationName())
                .locationType(loc.getLocationType()).orgId(loc.getOrgId())
                .street(loc.getStreet()).city(loc.getCity())
                .state(loc.getState()).pincode(loc.getPincode())
                .country(loc.getCountry()).build();
    }
}
