package com.bhagwat.scm.transportPlanner.dto;
import lombok.*;
import java.math.BigDecimal;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConsignmentItemDto {
    private String itemId;
    private String consignmentId;
    private String rtsId;
    private String rtsItemId;
    private String sellerId;
    private String productId;
    private String productName;
    private String skuId;
    private BigDecimal quantity;
    private BigDecimal weightKg;
    private BigDecimal volumeM3;
    private Integer packages;
}
