package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A TransportOrder is the execution instruction derived from a TransportPlan leg.
 * It is sent to carrierService for physical execution (vehicle dispatch, driver assignment, tracking).
 * One TransportPlan may produce multiple TransportOrders (one per leg or per consolidated route).
 */
@Entity @Table(name = "transport_orders")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportOrder {

    @Id @Column(name = "to_id", nullable = false, updatable = false)
    private String toId;

    @Column(name = "to_number", unique = true, nullable = false, length = 30)
    private String toNumber;

    @Column(name = "plan_id", nullable = false, length = 100)
    private String planId;

    @Column(name = "plan_number", length = 30)
    private String planNumber;

    @Column(name = "leg_id", length = 100)
    private String legId;

    @Column(name = "carrier_id", nullable = false, length = 100)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_type", length = 30)
    private ShipmentType shipmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 20)
    private TransportMode transportMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "load_type", length = 20)
    private LoadType loadType;

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

    @Column(name = "total_weight_kg", precision = 12, scale = 3)
    private BigDecimal totalWeightKg;

    @Column(name = "total_volume_m3", precision = 12, scale = 4)
    private BigDecimal totalVolumeM3;

    @Column(name = "total_packages")
    private Integer totalPackages;

    @Column(name = "total_distance_km", precision = 10, scale = 2)
    private BigDecimal totalDistanceKm;

    @Column(name = "freight_cost", precision = 14, scale = 2)
    private BigDecimal freightCost;

    @Column(name = "currency", length = 5)
    private String currency;

    // Reference to the TransportShipment created in carrierService
    @Column(name = "transport_shipment_id", length = 100)
    private String transportShipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30)
    @Builder.Default
    private TransportOrderStatus status = TransportOrderStatus.CREATED;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "to_id")
    @Builder.Default
    private List<TransportOrderItem> items = new ArrayList<>();

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "custom_data", columnDefinition = "jsonb")
    private java.util.Map<String, Object> customData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (toId == null) toId = UUID.randomUUID().toString();
        createdAt = updatedAt = LocalDateTime.now();
        if (status == null) status = TransportOrderStatus.CREATED;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
