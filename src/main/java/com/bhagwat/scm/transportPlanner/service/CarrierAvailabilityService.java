package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.dto.*;
import com.bhagwat.scm.transportPlanner.entity.*;
import com.bhagwat.scm.transportPlanner.repository.CarrierAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CarrierAvailabilityService {

    private final CarrierAvailabilityRepository availabilityRepository;

    @Transactional
    public CarrierAvailabilityResponse addAvailability(CarrierAvailabilityRequest request) {
        CarrierAvailability availability = CarrierAvailability.builder()
                .carrierId(request.getCarrierId())
                .carrierName(request.getCarrierName())
                .vehicleId(request.getVehicleId())
                .vehicleNumber(request.getVehicleNumber())
                .vehicleType(request.getVehicleType())
                .driverId(request.getDriverId())
                .driverName(request.getDriverName())
                .driverPhone(request.getDriverPhone())
                .transportMode(request.getTransportMode())
                .baseLocation(toLocation(request.getBaseLocation()))
                .availableFrom(request.getAvailableFrom())
                .availableTo(request.getAvailableTo())
                .capacityKg(request.getCapacityKg())
                .capacityCbm(request.getCapacityCbm())
                .servicePincodes(request.getServicePincodes())
                .build();
        return toResponse(availabilityRepository.save(availability));
    }

    @Transactional(readOnly = true)
    public List<CarrierAvailabilityResponse> listByCarrier(String carrierId) {
        return availabilityRepository.findByCarrierId(carrierId).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarrierAvailabilityResponse> listAvailable() {
        return availabilityRepository.findByIsBookedFalse().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public CarrierAvailabilityResponse markBooked(String availabilityId) {
        CarrierAvailability a = availabilityRepository.findById(availabilityId)
                .orElseThrow(() -> new RuntimeException("Availability not found: " + availabilityId));
        a.setIsBooked(true);
        return toResponse(availabilityRepository.save(a));
    }

    @Transactional
    public void deleteAvailability(String availabilityId) {
        availabilityRepository.deleteById(availabilityId);
    }

    // ── Mapper ────────────────────────────────────────────────────────────────

    private CarrierAvailabilityResponse toResponse(CarrierAvailability a) {
        return CarrierAvailabilityResponse.builder()
                .availabilityId(a.getAvailabilityId())
                .carrierId(a.getCarrierId())
                .carrierName(a.getCarrierName())
                .vehicleId(a.getVehicleId())
                .vehicleNumber(a.getVehicleNumber())
                .vehicleType(a.getVehicleType())
                .driverId(a.getDriverId())
                .driverName(a.getDriverName())
                .driverPhone(a.getDriverPhone())
                .transportMode(a.getTransportMode())
                .baseLocation(toLocationDto(a.getBaseLocation()))
                .availableFrom(a.getAvailableFrom())
                .availableTo(a.getAvailableTo())
                .capacityKg(a.getCapacityKg())
                .capacityCbm(a.getCapacityCbm())
                .servicePincodes(a.getServicePincodes())
                .isBooked(a.getIsBooked())
                .createdAt(a.getCreatedAt())
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
