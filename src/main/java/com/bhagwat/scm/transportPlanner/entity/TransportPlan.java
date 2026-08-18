package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity @Table(name = "transport_plans")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportPlan {

    @Id @Column(name = "plan_id", nullable = false, updatable = false)
    private String planId;

    @Column(name = "plan_number", unique = true, nullable = false, length = 30)
    private String planNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", length = 30)
    private PlanType planType;

    /** Primary RTS (for DIRECT / DESTINATION_CONSOLIDATION) */
    @Column(name = "rts_id", length = 100)
    private String rtsId;

    /** All RTS IDs included in this plan (stored as comma-separated for simplicity) */
    @Column(name = "rts_ids", columnDefinition = "TEXT")
    private String rtsIds;

    @Column(name = "carrier_id", length = 100)
    private String carrierId;

    @Column(name = "carrier_name", length = 255)
    private String carrierName;

    /** CBR response that triggered this plan */
    @Column(name = "cbr_response_id", length = 100)
    private String cbrResponseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_type", length = 30)
    private ShipmentType shipmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 20)
    private TransportMode transportMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "load_type", length = 20)
    private LoadType loadType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private TransportPlanStatus status = TransportPlanStatus.DRAFT;

    // ── Origin (primary / single source for DIRECT & DEST_CONSOL) ─────────────
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

    // ── Destination (primary / single destination for DIRECT & SRC_CONSOL) ───
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

    // ── Hub location (CROSSDOCK only) ────────────────────────────────────────
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "locationId",   column = @Column(name = "hub_location_id")),
        @AttributeOverride(name = "locationName", column = @Column(name = "hub_location_name")),
        @AttributeOverride(name = "locationType", column = @Column(name = "hub_location_type")),
        @AttributeOverride(name = "orgId",        column = @Column(name = "hub_org_id")),
        @AttributeOverride(name = "street",       column = @Column(name = "hub_street")),
        @AttributeOverride(name = "city",         column = @Column(name = "hub_city")),
        @AttributeOverride(name = "state",        column = @Column(name = "hub_state")),
        @AttributeOverride(name = "pincode",      column = @Column(name = "hub_pincode")),
        @AttributeOverride(name = "country",      column = @Column(name = "hub_country"))
    })
    private PlanLocation hubLocation;

    @Column(name = "planned_start_date_time")
    private LocalDateTime plannedStartDateTime;

    @Column(name = "planned_end_date_time")
    private LocalDateTime plannedEndDateTime;

    @Column(name = "actual_start_date_time")
    private LocalDateTime actualStartDateTime;

    @Column(name = "actual_end_date_time")
    private LocalDateTime actualEndDateTime;

    @Column(name = "total_distance_km", precision = 10, scale = 2)
    private BigDecimal totalDistanceKm;

    @Column(name = "total_weight_kg", precision = 12, scale = 3)
    private BigDecimal totalWeightKg;

    @Column(name = "total_volume_m3", precision = 12, scale = 4)
    private BigDecimal totalVolumeM3;

    @Column(name = "total_packages")
    private Integer totalPackages;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** All legs across all phases */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "plan_id")
    @Builder.Default
    private List<TransportPlanLeg> legs = new ArrayList<>();

    /** Line items consolidated from all RTSes */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "plan_id")
    @Builder.Default
    private List<TransportPlanOrder> orders = new ArrayList<>();

    /** Consignments (CROSSDOCK / DESTINATION_CONSOLIDATION) */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "plan_id")
    @Builder.Default
    private List<Consignment> consignments = new ArrayList<>();

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "custom_data", columnDefinition = "jsonb")
    private java.util.Map<String, Object> customData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (planId == null) planId = UUID.randomUUID().toString();
        createdAt = updatedAt = LocalDateTime.now();
        if (status == null) status = TransportPlanStatus.DRAFT;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
