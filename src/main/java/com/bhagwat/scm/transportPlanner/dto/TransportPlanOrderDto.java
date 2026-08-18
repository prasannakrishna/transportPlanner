package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.PlanOrderStatus;
import lombok.*;
import java.math.BigDecimal;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportPlanOrderDto {
    private String planOrderId;
    private String planId;
    private String rtsItemId;
    private String orderNumber;
    private String orderLineId;
    private String productId;
    private String productName;
    private String skuId;
    private BigDecimal quantity;
    private BigDecimal weightKg;
    private BigDecimal volumeM3;
    private PlanOrderStatus status;
}
