package com.bhagwat.scm.transportPlanner.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "transport_order_items")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportOrderItem {

    @Id @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "to_id", nullable = false, length = 100)
    private String toId;

    @Column(name = "rts_id", length = 100)
    private String rtsId;

    @Column(name = "consignment_id", length = 100)
    private String consignmentId;

    @Column(name = "order_number", length = 100)
    private String orderNumber;

    @Column(name = "sku_id", length = 100)
    private String skuId;

    @Column(name = "product_name", length = 255)
    private String productName;

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
