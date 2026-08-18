package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.LegStatus;
import lombok.*;
import java.time.LocalDateTime;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UpdateLegStatusRequest {
    private LegStatus status;
    private LocalDateTime actualPickupDateTime;
    private LocalDateTime actualDeliveryDateTime;
}
