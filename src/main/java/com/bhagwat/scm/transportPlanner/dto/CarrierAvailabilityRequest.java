package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.TransportMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CarrierAvailabilityRequest {
    @NotBlank private String carrierId;
    private String carrierName;
    private String vehicleId;
    private String vehicleNumber;
    private String vehicleType;
    private String driverId;
    private String driverName;
    private String driverPhone;
    private TransportMode transportMode;
    private PlanLocationDto baseLocation;
    @NotNull private LocalDateTime availableFrom;
    @NotNull private LocalDateTime availableTo;
    private BigDecimal capacityKg;
    private BigDecimal capacityCbm;
    private String servicePincodes;
}
