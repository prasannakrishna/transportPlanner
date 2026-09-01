package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.entity.*;
import com.bhagwat.scm.transportPlanner.enums.*;
import com.bhagwat.scm.transportPlanner.kafka.TransportPlanKafkaProducer;
import com.bhagwat.scm.transportPlanner.repository.TransportOrderRepository;
import com.bhagwat.scm.transportPlanner.repository.TransportPlanLegRepository;
import com.bhagwat.scm.transportPlanner.repository.TransportPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransportOrderService {

    private final TransportOrderRepository toRepo;
    private final TransportPlanRepository planRepo;
    private final TransportPlanLegRepository legRepo;
    private final TransportPlanKafkaProducer kafkaProducer;

    /**
     * Generate TransportOrders from an ACTIVE plan — one TO per leg.
     * This is called when plan transitions to ACTIVE.
     */
    @Transactional
    public List<TransportOrder> generateFromPlan(String planId) {
        TransportPlan plan = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        List<TransportOrder> orders = new ArrayList<>();
        for (TransportPlanLeg leg : plan.getLegs()) {
            String toNumber = "TO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            TransportOrder to = TransportOrder.builder()
                    .toNumber(toNumber)
                    .planId(plan.getPlanId())
                    .planNumber(plan.getPlanNumber())
                    .rtsId(plan.getRtsId())
                    .legId(leg.getLegId())
                    .carrierId(leg.getCarrierId() != null ? leg.getCarrierId() : plan.getCarrierId())
                    .carrierName(leg.getCarrierName() != null ? leg.getCarrierName() : plan.getCarrierName())
                    .vehicleId(leg.getVehicleId())
                    .vehicleNumber(leg.getVehicleNumber())
                    .shipmentType(plan.getShipmentType())
                    .transportMode(leg.getTransportMode() != null ? leg.getTransportMode() : plan.getTransportMode())
                    .loadType(plan.getLoadType())
                    .originLocation(leg.getOriginLocation())
                    .destinationLocation(leg.getDestinationLocation())
                    .plannedPickupDateTime(leg.getPlannedPickupDateTime())
                    .plannedDeliveryDateTime(leg.getPlannedDeliveryDateTime())
                    .totalWeightKg(plan.getTotalWeightKg())
                    .totalVolumeM3(plan.getTotalVolumeM3())
                    .totalPackages(plan.getTotalPackages())
                    .totalDistanceKm(leg.getDistanceKm())
                    .status(TransportOrderStatus.CREATED)
                    .build();

            // Copy plan orders as TO items
            List<TransportOrderItem> items = plan.getOrders().stream().map(po ->
                    TransportOrderItem.builder()
                            .rtsId(po.getRtsItemId())
                            .orderNumber(po.getOrderNumber())
                            .skuId(po.getSkuId())
                            .productName(po.getProductName())
                            .quantity(po.getQuantity())
                            .weightKg(po.getWeightKg())
                            .volumeM3(po.getVolumeM3())
                            .build()
            ).toList();
            to.setItems(items);

            orders.add(toRepo.save(to));
        }

        // Publish event for carrierService to create shipments
        orders.forEach(to -> kafkaProducer.publishTransportOrderCreated(to));

        return orders;
    }

    @Transactional
    public TransportOrder updateStatus(String toId, TransportOrderStatus status) {
        TransportOrder to = toRepo.findById(toId)
                .orElseThrow(() -> new IllegalArgumentException("TO not found: " + toId));
        to.setStatus(status);

        // If carrier assigned vehicle/driver, update leg too
        if (status == TransportOrderStatus.VEHICLE_ASSIGNED && to.getLegId() != null) {
            legRepo.findById(to.getLegId()).ifPresent(leg -> {
                leg.setVehicleId(to.getVehicleId());
                leg.setVehicleNumber(to.getVehicleNumber());
                legRepo.save(leg);
            });
        }

        // If execution complete, update leg status
        if (status == TransportOrderStatus.COMPLETED && to.getLegId() != null) {
            legRepo.findById(to.getLegId()).ifPresent(leg -> {
                leg.setStatus(LegStatus.COMPLETED);
                leg.setActualDeliveryDateTime(to.getActualDeliveryDateTime());
                legRepo.save(leg);
            });
        }

        return toRepo.save(to);
    }

    @Transactional
    public TransportOrder assignVehicleAndDriver(String toId, String vehicleId, String vehicleNumber,
                                                  String driverId, String driverName) {
        TransportOrder to = toRepo.findById(toId)
                .orElseThrow(() -> new IllegalArgumentException("TO not found: " + toId));
        to.setVehicleId(vehicleId);
        to.setVehicleNumber(vehicleNumber);
        to.setDriverId(driverId);
        to.setDriverName(driverName);
        to.setStatus(TransportOrderStatus.VEHICLE_ASSIGNED);
        return toRepo.save(to);
    }

    @Transactional
    public TransportOrder linkShipment(String toId, String transportShipmentId) {
        TransportOrder to = toRepo.findById(toId)
                .orElseThrow(() -> new IllegalArgumentException("TO not found: " + toId));
        to.setTransportShipmentId(transportShipmentId);
        to.setStatus(TransportOrderStatus.IN_EXECUTION);
        return toRepo.save(to);
    }

    public TransportOrder get(String toId) {
        return toRepo.findById(toId)
                .orElseThrow(() -> new IllegalArgumentException("TO not found: " + toId));
    }

    public List<TransportOrder> getByPlan(String planId) {
        return toRepo.findByPlanId(planId);
    }

    public List<TransportOrder> getByCarrier(String carrierId) {
        return toRepo.findByCarrierId(carrierId);
    }

    public List<TransportOrder> getPendingForCarrier(String carrierId) {
        return toRepo.findByCarrierIdAndStatus(carrierId, TransportOrderStatus.DISPATCHED_TO_CARRIER);
    }
}
