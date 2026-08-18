package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RouteTemplateResponse {
    private String templateId;
    private String templateName;
    private ShipmentType shipmentType;
    private TransportMode transportMode;
    private String originPincode;
    private String destinationPincode;
    private String originCity;
    private String destinationCity;
    private BigDecimal estimatedTransitHours;
    private BigDecimal distanceKm;
    private String viaHubs;
    private Integer legCount;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
