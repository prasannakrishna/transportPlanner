package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.ConsignmentStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A consignment groups all items destined for the same location (used in CROSSDOCK and DESTINATION_CONSOLIDATION plans).
 * Phase-1 legs bring goods from sources to hub; phase-2 leg delivers this consignment from hub to destination.
 */
@Entity @Table(name = "consignments")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Consignment {
    @Id @Column(name = "consignment_id", nullable = false, updatable = false)
    private String consignmentId;

    @Column(name = "consignment_number", unique = true, nullable = false, length = 30)
    private String consignmentNumber;

    @Column(name = "plan_id", nullable = false, length = 100)
    private String planId;

    /** Phase-2 leg: Hub → this destination */
    @Column(name = "delivery_leg_id", length = 100)
    private String deliveryLegId;

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

    @Column(name = "total_weight_kg", precision = 12, scale = 3)
    private BigDecimal totalWeightKg;

    @Column(name = "total_volume_m3", precision = 12, scale = 4)
    private BigDecimal totalVolumeM3;

    @Column(name = "total_packages")
    private Integer totalPackages;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30)
    @Builder.Default
    private ConsignmentStatus status = ConsignmentStatus.CREATED;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "consignment_id")
    @Builder.Default
    private List<ConsignmentItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (consignmentId == null) consignmentId = UUID.randomUUID().toString();
        createdAt = updatedAt = LocalDateTime.now();
        if (status == null) status = ConsignmentStatus.CREATED;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
