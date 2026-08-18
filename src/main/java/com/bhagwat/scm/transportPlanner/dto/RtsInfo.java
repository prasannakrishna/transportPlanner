package com.bhagwat.scm.transportPlanner.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * Represents one ReadyToShipOrder to be included in a transport plan.
 * For Plan 3 (DESTINATION_CONSOLIDATION), pass additionalDestinations.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RtsInfo {
    @NotBlank
    private String rtsId;
    private String rtsNumber;
    private String sellerId;
    /** Seller-Logistics contract ID — used to verify partner network consent for Plan 4 */
    private String sellerContractId;

    @NotNull
    private PlanLocationDto sourceLocation;

    /** Primary destination (warehouse/store/customer) */
    @NotNull
    private PlanLocationDto destinationLocation;

    /** Additional customer destinations — used for Plan 3 (e-com multi-drop) */
    private List<PlanLocationDto> additionalDestinations;

    private BigDecimal totalWeightKg;
    private BigDecimal totalVolumeM3;
    private Integer totalPackages;
    private List<RtsItemSummary> items;
}
