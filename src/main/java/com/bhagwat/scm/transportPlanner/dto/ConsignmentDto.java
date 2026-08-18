package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.ConsignmentStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConsignmentDto {
    private String consignmentId;
    private String consignmentNumber;
    private String planId;
    private String deliveryLegId;
    private PlanLocationDto destinationLocation;
    private BigDecimal totalWeightKg;
    private BigDecimal totalVolumeM3;
    private Integer totalPackages;
    private ConsignmentStatus status;
    private List<ConsignmentItemDto> items;
    private LocalDateTime createdAt;
}
