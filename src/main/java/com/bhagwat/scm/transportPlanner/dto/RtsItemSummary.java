package com.bhagwat.scm.transportPlanner.dto;
import lombok.*;
import java.math.BigDecimal;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class RtsItemSummary {
    private String rtsItemId;
    private String productId;
    private String productName;
    private String skuId;
    private BigDecimal quantity;
    private BigDecimal weightKg;
    private BigDecimal volumeM3;
    private Integer packages;
}
