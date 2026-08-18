package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import java.math.BigDecimal;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RouteTemplateRequest {
    @NotBlank private String templateName;
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
}
