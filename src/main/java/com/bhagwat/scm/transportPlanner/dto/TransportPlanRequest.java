package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportPlanRequest {
    @NotBlank private String rtsId;
    private String rtsNumber;
    @NotBlank private String carrierId;
    private String carrierName;
    @NotNull private ShipmentType shipmentType;
    private TransportMode transportMode;
    private LoadType loadType;
    private PlanLocationDto originLocation;
    private PlanLocationDto destinationLocation;
    private LocalDateTime plannedStartDateTime;
    private LocalDateTime plannedEndDateTime;
    private BigDecimal totalDistanceKm;
    private BigDecimal totalWeightKg;
    private BigDecimal totalVolumeM3;
    private Integer totalPackages;
    private String notes;
    private List<TransportPlanOrderDto> orders;
}
