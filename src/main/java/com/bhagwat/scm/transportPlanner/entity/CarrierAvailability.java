package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "carrier_availability")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CarrierAvailability {
    @Id @Column(name = "availability_id", nullable = false, updatable = false)
    private String availabilityId;

    @Column(name = "carrier_id", nullable = false, length = 100)
    private String carrierId;

    @Column(name = "carrier_name", length = 255)
    private String carrierName;

    @Column(name = "vehicle_id", length = 100)
    private String vehicleId;

    @Column(name = "vehicle_number", length = 30)
    private String vehicleNumber;

    @Column(name = "vehicle_type", length = 30)
    private String vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 20)
    private TransportMode transportMode;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "locationId",   column = @Column(name = "base_location_id")),
        @AttributeOverride(name = "locationName", column = @Column(name = "base_location_name")),
        @AttributeOverride(name = "locationType", column = @Column(name = "base_location_type")),
        @AttributeOverride(name = "orgId",        column = @Column(name = "base_org_id")),
        @AttributeOverride(name = "street",       column = @Column(name = "base_street")),
        @AttributeOverride(name = "city",         column = @Column(name = "base_city")),
        @AttributeOverride(name = "state",        column = @Column(name = "base_state")),
        @AttributeOverride(name = "pincode",      column = @Column(name = "base_pincode")),
        @AttributeOverride(name = "country",      column = @Column(name = "base_country"))
    })
    private PlanLocation baseLocation;

    @Column(name = "available_from")
    private LocalDateTime availableFrom;

    @Column(name = "available_to")
    private LocalDateTime availableTo;

    @Column(name = "capacity_kg", precision = 12, scale = 3)
    private BigDecimal capacityKg;

    @Column(name = "capacity_cbm", precision = 10, scale = 4)
    private BigDecimal capacityCbm;

    @Column(name = "service_pincodes", columnDefinition = "TEXT")
    private String servicePincodes;

    @Column(name = "is_booked")
    @Builder.Default
    private Boolean isBooked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (availabilityId == null) availabilityId = UUID.randomUUID().toString();
        createdAt = updatedAt = LocalDateTime.now();
        if (isBooked == null) isBooked = false;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
