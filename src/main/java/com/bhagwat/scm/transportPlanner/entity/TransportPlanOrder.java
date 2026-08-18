package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.PlanOrderStatus;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity @Table(name = "transport_plan_orders")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TransportPlanOrder {
    @Id @Column(name = "plan_order_id", nullable = false, updatable = false)
    private String planOrderId;

    @Column(name = "plan_id", nullable = false, length = 100)
    private String planId;

    @Column(name = "rts_item_id", length = 100)
    private String rtsItemId;

    @Column(name = "order_number", length = 100)
    private String orderNumber;

    @Column(name = "order_line_id", length = 100)
    private String orderLineId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private PlanOrderStatus status = PlanOrderStatus.PENDING;

    @PrePersist
    protected void onCreate() {
        if (planOrderId == null) planOrderId = UUID.randomUUID().toString();
        if (status == null) status = PlanOrderStatus.PENDING;
    }
}
