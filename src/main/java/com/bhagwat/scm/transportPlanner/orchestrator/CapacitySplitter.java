package com.bhagwat.scm.transportPlanner.orchestrator;

import com.bhagwat.scm.transportPlanner.dto.RtsInfo;
import com.bhagwat.scm.transportPlanner.entity.CarrierAvailability;
import com.bhagwat.scm.transportPlanner.enums.TransportMode;
import com.bhagwat.scm.transportPlanner.repository.CarrierAvailabilityRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Splits RTS order groups when total weight/volume exceeds carrier vehicle capacity.
 * Also validates vehicle availability before plan activation (best-fit selection).
 *
 * Splitting strategy: First-Fit Decreasing (FFD) bin-packing by weight.
 *   1. Sort RTS orders by weight descending
 *   2. For each order, try to fit into existing group (bin)
 *   3. If no bin has remaining capacity, create a new bin
 *   4. Each bin becomes a separate TransportPlan
 *
 * Vehicle selection strategy: Best-Fit (smallest sufficient capacity).
 *   - Query available vehicles for carrier + transport mode + date
 *   - Select the vehicle with smallest capacity >= required
 *   - Minimizes wasted space (reduces cost for LTL)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CapacitySplitter {

    private final CarrierAvailabilityRepository availabilityRepo;

    private static final BigDecimal DEFAULT_VEHICLE_CAPACITY_KG = BigDecimal.valueOf(10000);
    private static final BigDecimal DEFAULT_VEHICLE_CAPACITY_CBM = BigDecimal.valueOf(40);

    /**
     * Split RTS orders into groups, each fitting within a single vehicle's capacity.
     * Uses First-Fit Decreasing bin-packing.
     *
     * @param rtsOrders   all RTS orders to distribute
     * @param carrierId   the assigned carrier
     * @param mode        transport mode (determines vehicle type/capacity)
     * @return list of sub-groups, each within one vehicle's capacity
     */
    public List<List<RtsInfo>> splitByCapacity(List<RtsInfo> rtsOrders, String carrierId, TransportMode mode) {
        // Determine vehicle capacity for this carrier
        BigDecimal maxCapacityKg = getMaxVehicleCapacity(carrierId, mode);

        // Calculate total weight
        BigDecimal totalWeight = rtsOrders.stream()
                .map(r -> r.getTotalWeightKg() != null ? r.getTotalWeightKg() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // If everything fits in one vehicle, no split needed
        if (totalWeight.compareTo(maxCapacityKg) <= 0) {
            return List.of(rtsOrders);
        }

        log.info("Splitting {} RTS orders (total {}kg) into groups of max {}kg for carrier={}",
                rtsOrders.size(), totalWeight, maxCapacityKg, carrierId);

        // Sort by weight descending (FFD heuristic)
        List<RtsInfo> sorted = new ArrayList<>(rtsOrders);
        sorted.sort(Comparator.comparing(
                (RtsInfo r) -> r.getTotalWeightKg() != null ? r.getTotalWeightKg() : BigDecimal.ZERO)
                .reversed());

        // Bin-packing: First-Fit Decreasing
        List<Bin> bins = new ArrayList<>();
        for (RtsInfo rts : sorted) {
            BigDecimal weight = rts.getTotalWeightKg() != null ? rts.getTotalWeightKg() : BigDecimal.ZERO;

            // Try to fit into an existing bin
            boolean fitted = false;
            for (Bin bin : bins) {
                if (bin.remainingCapacity.compareTo(weight) >= 0) {
                    bin.orders.add(rts);
                    bin.remainingCapacity = bin.remainingCapacity.subtract(weight);
                    fitted = true;
                    break;
                }
            }

            // No existing bin has space — create new one
            if (!fitted) {
                Bin newBin = new Bin(maxCapacityKg);
                newBin.orders.add(rts);
                newBin.remainingCapacity = maxCapacityKg.subtract(weight);
                bins.add(newBin);
            }
        }

        log.info("Split into {} groups (from {} orders, {}kg total)",
                bins.size(), rtsOrders.size(), totalWeight);

        return bins.stream().map(b -> b.orders).toList();
    }

    /**
     * Validate that the carrier has an available vehicle with sufficient capacity
     * for the planned date, with a driver actually able to drive it. Returns the
     * best-fit vehicle+driver assignment.
     *
     * @param carrierId    carrier to check
     * @param mode         transport mode
     * @param weightKg     required weight capacity
     * @param volumeM3     required volume capacity (nullable)
     * @param plannedDate  when the vehicle (and driver) are needed — must fall
     *                     within the availability slot's [availableFrom, availableTo]
     *                     window; a null plannedDate skips the date check entirely
     * @return vehicle assignment if available, empty if no suitable vehicle
     */
    public Optional<VehicleAssignment> validateAndAssign(String carrierId, TransportMode mode,
                                                          BigDecimal weightKg, BigDecimal volumeM3,
                                                          LocalDateTime plannedDate) {
        // Query available (unbooked) vehicles for this carrier
        List<CarrierAvailability> available = availabilityRepo.findByCarrierIdAndIsBookedFalse(carrierId);

        if (available.isEmpty()) {
            log.warn("No available vehicles for carrier={} mode={}", carrierId, mode);
            return Optional.empty();
        }

        // Filter by sufficient capacity, transport mode, and — this was previously
        // accepted as a parameter but never actually checked — the planned date
        // falling within the slot's availability window.
        BigDecimal requiredWeight = weightKg != null ? weightKg : BigDecimal.ZERO;
        BigDecimal requiredVolume = volumeM3 != null ? volumeM3 : BigDecimal.ZERO;

        List<CarrierAvailability> sufficient = available.stream()
                .filter(a -> a.getCapacityKg() != null && a.getCapacityKg().compareTo(requiredWeight) >= 0)
                .filter(a -> requiredVolume.compareTo(BigDecimal.ZERO) == 0
                        || (a.getCapacityCbm() != null && a.getCapacityCbm().compareTo(requiredVolume) >= 0))
                .filter(a -> mode == null || mode == a.getTransportMode())
                .filter(a -> plannedDate == null || isWithinWindow(a, plannedDate))
                .toList();

        if (sufficient.isEmpty()) {
            log.warn("No vehicle with sufficient capacity for carrier={} weight={}kg volume={}m³ on {}",
                    carrierId, requiredWeight, requiredVolume, plannedDate);
            return Optional.empty();
        }

        // Best-fit: prefer a slot with a driver already paired (a truck nobody can
        // drive isn't dispatch-ready), then smallest sufficient capacity to
        // minimize waste. Falling back to a driver-less slot rather than failing
        // outright avoids blocking every activation while driver-pairing data is
        // still being backfilled — but it's logged loudly since it means this
        // booking isn't actually dispatch-ready yet.
        CarrierAvailability bestFit = sufficient.stream()
                .sorted(Comparator
                        .comparing((CarrierAvailability a) -> a.getDriverId() == null)
                        .thenComparing(CarrierAvailability::getCapacityKg))
                .findFirst()
                .orElse(sufficient.get(0));

        if (bestFit.getDriverId() == null) {
            log.warn("Vehicle {} assigned for carrier={} but has NO driver paired — not actually dispatch-ready for {}",
                    bestFit.getVehicleNumber(), carrierId, plannedDate);
        } else {
            log.info("Vehicle+driver assigned: carrier={} vehicle={} driver={} capacity={}kg (required={}kg) for {}",
                    carrierId, bestFit.getVehicleNumber(), bestFit.getDriverName(), bestFit.getCapacityKg(), requiredWeight, plannedDate);
        }

        return Optional.of(VehicleAssignment.builder()
                .availabilityId(bestFit.getAvailabilityId())
                .vehicleId(bestFit.getVehicleId())
                .vehicleNumber(bestFit.getVehicleNumber())
                .driverId(bestFit.getDriverId())
                .driverName(bestFit.getDriverName())
                .driverPhone(bestFit.getDriverPhone())
                .capacityKg(bestFit.getCapacityKg())
                .capacityCbm(bestFit.getCapacityCbm())
                .build());
    }

    private boolean isWithinWindow(CarrierAvailability a, LocalDateTime plannedDate) {
        boolean afterStart = a.getAvailableFrom() == null || !plannedDate.isBefore(a.getAvailableFrom());
        boolean beforeEnd = a.getAvailableTo() == null || !plannedDate.isAfter(a.getAvailableTo());
        return afterStart && beforeEnd;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Largest vehicle capacity (kg) this carrier has on file — used both for
     * bin-packing splits here and for consolidation-window decisions in
     * PlanAmendmentService (is a plan full enough to close and activate?).
     */
    public BigDecimal getMaxVehicleCapacity(String carrierId, TransportMode mode) {
        // Try to get from largest available vehicle for this carrier
        List<CarrierAvailability> vehicles = availabilityRepo.findByCarrierId(carrierId);
        if (!vehicles.isEmpty()) {
            return vehicles.stream()
                    .map(v -> v.getCapacityKg() != null ? v.getCapacityKg() : DEFAULT_VEHICLE_CAPACITY_KG)
                    .max(BigDecimal::compareTo)
                    .orElse(DEFAULT_VEHICLE_CAPACITY_KG);
        }
        return DEFAULT_VEHICLE_CAPACITY_KG;
    }

    private static class Bin {
        final List<RtsInfo> orders = new ArrayList<>();
        BigDecimal remainingCapacity;

        Bin(BigDecimal capacity) {
            this.remainingCapacity = capacity;
        }
    }

    // ── Result DTO ───────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class VehicleAssignment {
        private String availabilityId;
        private String vehicleId;
        private String vehicleNumber;
        private String driverId;
        private String driverName;
        private String driverPhone;
        private BigDecimal capacityKg;
        private BigDecimal capacityCbm;
    }
}
