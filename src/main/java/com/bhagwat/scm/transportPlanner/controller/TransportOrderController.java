package com.bhagwat.scm.transportPlanner.controller;

import com.bhagwat.scm.transportPlanner.entity.TransportOrder;
import com.bhagwat.scm.transportPlanner.enums.TransportOrderStatus;
import com.bhagwat.scm.transportPlanner.service.TransportOrderService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transport/orders")
@RequiredArgsConstructor
public class TransportOrderController {

    private final TransportOrderService toService;

    /** Generate TOs from an active plan (one per leg) */
    @PostMapping("/generate/{planId}")
    public ResponseEntity<List<TransportOrder>> generate(@PathVariable String planId) {
        return ResponseEntity.ok(toService.generateFromPlan(planId));
    }

    @GetMapping("/{toId}")
    public ResponseEntity<TransportOrder> get(@PathVariable String toId) {
        return ResponseEntity.ok(toService.get(toId));
    }

    @GetMapping("/plan/{planId}")
    public ResponseEntity<List<TransportOrder>> getByPlan(@PathVariable String planId) {
        return ResponseEntity.ok(toService.getByPlan(planId));
    }

    @GetMapping("/carrier/{carrierId}")
    public ResponseEntity<List<TransportOrder>> getByCarrier(@PathVariable String carrierId) {
        return ResponseEntity.ok(toService.getByCarrier(carrierId));
    }

    @GetMapping("/carrier/{carrierId}/pending")
    public ResponseEntity<List<TransportOrder>> getPendingForCarrier(@PathVariable String carrierId) {
        return ResponseEntity.ok(toService.getPendingForCarrier(carrierId));
    }

    @PatchMapping("/{toId}/status")
    public ResponseEntity<TransportOrder> updateStatus(@PathVariable String toId,
                                                       @RequestParam TransportOrderStatus status) {
        return ResponseEntity.ok(toService.updateStatus(toId, status));
    }

    @PatchMapping("/{toId}/assign")
    public ResponseEntity<TransportOrder> assign(@PathVariable String toId,
                                                  @RequestBody AssignRequest req) {
        return ResponseEntity.ok(toService.assignVehicleAndDriver(
                toId, req.getVehicleId(), req.getVehicleNumber(), req.getDriverId(), req.getDriverName()));
    }

    @PatchMapping("/{toId}/link-shipment")
    public ResponseEntity<TransportOrder> linkShipment(@PathVariable String toId,
                                                       @RequestParam String shipmentId) {
        return ResponseEntity.ok(toService.linkShipment(toId, shipmentId));
    }

    @Data
    public static class AssignRequest {
        private String vehicleId;
        private String vehicleNumber;
        private String driverId;
        private String driverName;
    }
}
