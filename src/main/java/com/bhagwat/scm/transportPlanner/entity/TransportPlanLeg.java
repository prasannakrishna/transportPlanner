package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "transport_plan_legs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportPlanLeg {
    @Id @Column(name = "leg_id", nullable = false, updatable = false)
    private String legId;

    @Column(name = "plan_id", nullable = false, length = 100)
    private String planId;

    @Column(name = "leg_sequence")
    private Integer legSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "leg_type", length = 20)
    private LegType legType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 20)
    private TransportMode transportMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private LegStatus status = LegStatus.PENDING;

    @Column(name = "carrier_id", length = 100)
    private String carrierId;

    @Column(name = "carrier_name", length = 255)
    private String carrierName;

    @Column(name = "vehicle_id", length = 100)
    private String vehicleId;

    @Column(name = "vehicle_number", length = 30)
    private String vehicleNumber;

    @Column(name = "driver_id", length = 100)
    private String driverId;

    @Column(name = "driver_name", length = 200)
    private String driverName;

    @Column(name = "driver_phone", length = 20)
    private String driverPhone;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "locationId",   column = @Column(name = "orig_location_id")),
        @AttributeOverride(name = "locationName", column = @Column(name = "orig_location_name")),
        @AttributeOverride(name = "locationType", column = @Column(name = "orig_location_type")),
        @AttributeOverride(name = "orgId",        column = @Column(name = "orig_org_id")),
        @AttributeOverride(name = "street",       column = @Column(name = "orig_street")),
        @AttributeOverride(name = "city",         column = @Column(name = "orig_city")),
        @AttributeOverride(name = "state",        column = @Column(name = "orig_state")),
        @AttributeOverride(name = "pincode",      column = @Column(name = "orig_pincode")),
        @AttributeOverride(name = "country",      column = @Column(name = "orig_country"))
    })
    private PlanLocation originLocation;

    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "locationId",   column = @Column(name = "dest_location_id")),
        @AttributeOverride(name = "locationName", column = @Column(name = "dest_location_name")),
        @AttributeOverride(name = "locationType", column = @Column(name = "dest_location_type")),
        @AttributeOverride(name = "orgId",        column = @Column(name = "dest_org_id")),
        @AttributeOverride(name = "street",       column = @Column(name = "dest_street")),
        @AttributeOverride(name = "city",         column = @Column(name = "dest_city")),
        @AttributeOverride(name = "state",        column = @Column(name = "dest_state")),
        @AttributeOverride(name = "pincode",      column = @Column(name = "dest_pincode")),
        @AttributeOverride(name = "country",      column = @Column(name = "dest_country"))
    })
    private PlanLocation destinationLocation;

    @Column(name = "planned_pickup_date_time")
    private LocalDateTime plannedPickupDateTime;

    @Column(name = "planned_delivery_date_time")
    private LocalDateTime plannedDeliveryDateTime;

    @Column(name = "actual_pickup_date_time")
    private LocalDateTime actualPickupDateTime;

    @Column(name = "actual_delivery_date_time")
    private LocalDateTime actualDeliveryDateTime;

    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (legId == null) legId = UUID.randomUUID().toString();
        if (status == null) status = LegStatus.PENDING;
    }
}
