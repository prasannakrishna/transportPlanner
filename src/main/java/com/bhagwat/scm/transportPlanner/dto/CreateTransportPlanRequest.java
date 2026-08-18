package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Master request to create any of the 4 transport plan types.
 *
 * Plan 1 DIRECT                  — 1 RtsInfo, 1 source, 1 destination
 * Plan 2 SOURCE_CONSOLIDATION    — N RtsInfo (different sources), same destinationLocation across all
 * Plan 3 DESTINATION_CONSOLIDATION — 1 RtsInfo with additionalDestinations list
 * Plan 4 CROSSDOCK               — N RtsInfo (different sources, different destinations), hubLocation required,
 *                                   all sellerContractId must have allowPartnerNetwork=true
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreateTransportPlanRequest {

    @NotNull
    private PlanType planType;

    @NotBlank
    private String carrierId;
    private String carrierName;

    /** CarrierBookingResponse ID that confirmed this carrier */
    private String cbrResponseId;

    @NotNull
    private ShipmentType shipmentType;
    private TransportMode transportMode;
    private LoadType loadType;

    @NotEmpty @Valid
    private List<RtsInfo> rtsOrders;

    /**
     * Hub warehouse for CROSSDOCK plan (must support cross-docking).
     * LocationType should be WAREHOUSE.
     */
    private PlanLocationDto hubLocation;

    private LocalDateTime plannedStartDateTime;
    private LocalDateTime plannedEndDateTime;
    private String notes;
}
