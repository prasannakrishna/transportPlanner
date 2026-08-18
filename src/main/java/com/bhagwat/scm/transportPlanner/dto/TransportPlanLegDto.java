package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportPlanLegDto {
    private String legId;
    private String planId;
    private Integer legSequence;
    private LegType legType;
    private TransportMode transportMode;
    private LegStatus status;
    private String carrierId;
    private String carrierName;
    private String vehicleId;
    private String vehicleNumber;
    private PlanLocationDto originLocation;
    private PlanLocationDto destinationLocation;
    private LocalDateTime plannedPickupDateTime;
    private LocalDateTime plannedDeliveryDateTime;
    private LocalDateTime actualPickupDateTime;
    private LocalDateTime actualDeliveryDateTime;
    private BigDecimal distanceKm;
    private String notes;
}
