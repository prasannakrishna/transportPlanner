package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportPlanResponse {
    private String planId;
    private String planNumber;
    private PlanType planType;
    private String rtsId;
    private List<String> rtsIds;
    private String carrierId;
    private String carrierName;
    private String cbrResponseId;
    private ShipmentType shipmentType;
    private TransportMode transportMode;
    private LoadType loadType;
    private TransportPlanStatus status;
    private PlanLocationDto originLocation;
    private PlanLocationDto destinationLocation;
    private PlanLocationDto hubLocation;
    private LocalDateTime plannedStartDateTime;
    private LocalDateTime plannedEndDateTime;
    private LocalDateTime actualStartDateTime;
    private LocalDateTime actualEndDateTime;
    private BigDecimal totalDistanceKm;
    private BigDecimal totalWeightKg;
    private BigDecimal totalVolumeM3;
    private Integer totalPackages;
    private String notes;
    private List<TransportPlanLegDto> legs;
    private List<TransportPlanOrderDto> orders;
    private List<ConsignmentDto> consignments;
    private LocalDateTime createdAt;
}
