package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity @Table(name = "route_templates")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RouteTemplate {
    @Id @Column(name = "template_id", nullable = false, updatable = false)
    private String templateId;

    @Column(name = "template_name", nullable = false, length = 255)
    private String templateName;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_type", length = 30)
    private ShipmentType shipmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode", length = 20)
    private TransportMode transportMode;

    @Column(name = "origin_pincode", length = 20)
    private String originPincode;

    @Column(name = "destination_pincode", length = 20)
    private String destinationPincode;

    @Column(name = "origin_city", length = 100)
    private String originCity;

    @Column(name = "destination_city", length = 100)
    private String destinationCity;

    @Column(name = "estimated_transit_hours", precision = 8, scale = 2)
    private BigDecimal estimatedTransitHours;

    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    @Column(name = "via_hubs", columnDefinition = "TEXT")
    private String viaHubs;

    @Column(name = "leg_count")
    private Integer legCount;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (templateId == null) templateId = UUID.randomUUID().toString();
        createdAt = updatedAt = LocalDateTime.now();
        if (isActive == null) isActive = true;
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
