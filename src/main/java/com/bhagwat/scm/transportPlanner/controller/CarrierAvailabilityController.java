package com.bhagwat.scm.transportPlanner.controller;

import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.service.CarrierAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transport/carrier-availability")
@RequiredArgsConstructor
@Tag(name = "Carrier Availability", description = "Manage carrier vehicle availability slots")
public class CarrierAvailabilityController {

    private final CarrierAvailabilityService availabilityService;

    @PostMapping
    @Operation(summary = "Register a carrier availability slot")
    public ResponseEntity<CarrierAvailabilityResponse> addAvailability(@Valid @RequestBody CarrierAvailabilityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(availabilityService.addAvailability(request));
    }

    @GetMapping("/carrier/{carrierId}")
    @Operation(summary = "List availability slots for a carrier")
    public ResponseEntity<List<CarrierAvailabilityResponse>> listByCarrier(@PathVariable String carrierId) {
        return ResponseEntity.ok(availabilityService.listByCarrier(carrierId));
    }

    @GetMapping("/available")
    @Operation(summary = "List all unbooked availability slots")
    public ResponseEntity<List<CarrierAvailabilityResponse>> listAvailable() {
        return ResponseEntity.ok(availabilityService.listAvailable());
    }

    @PatchMapping("/{availabilityId}/book")
    @Operation(summary = "Mark an availability slot as booked")
    public ResponseEntity<CarrierAvailabilityResponse> markBooked(@PathVariable String availabilityId) {
        return ResponseEntity.ok(availabilityService.markBooked(availabilityId));
    }

    @DeleteMapping("/{availabilityId}")
    @Operation(summary = "Delete an availability slot")
    public ResponseEntity<Void> deleteAvailability(@PathVariable String availabilityId) {
        availabilityService.deleteAvailability(availabilityId);
        return ResponseEntity.noContent().build();
    }
}
