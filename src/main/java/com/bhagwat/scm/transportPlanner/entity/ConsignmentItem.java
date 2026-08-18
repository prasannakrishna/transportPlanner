package com.bhagwat.scm.transportPlanner.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "consignment_items")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ConsignmentItem {
    @Id @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "consignment_id", nullable = false, length = 100)
    private String consignmentId;

    @Column(name = "rts_id", length = 100)
    private String rtsId;

    @Column(name = "rts_item_id", length = 100)
    private String rtsItemId;

    @Column(name = "seller_id", length = 100)
    private String sellerId;

    @Column(name = "product_id", length = 100)
    private String productId;

    @Column(name = "product_name", length = 255)
    private String productName;

    @Column(name = "sku_id", length = 100)
    private String skuId;

    @Column(name = "quantity", precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "weight_kg", precision = 12, scale = 3)
    private BigDecimal weightKg;

    @Column(name = "volume_m3", precision = 12, scale = 4)
    private BigDecimal volumeM3;

    @Column(name = "packages")
    private Integer packages;

    @PrePersist
    protected void onCreate() {
        if (itemId == null) itemId = UUID.randomUUID().toString();
    }
}
