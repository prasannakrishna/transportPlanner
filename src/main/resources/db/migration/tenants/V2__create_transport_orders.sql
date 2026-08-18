-- V2__create_transport_orders.sql

CREATE TABLE transport_orders (
    to_id                     VARCHAR(100) PRIMARY KEY,
    to_number                 VARCHAR(30)  NOT NULL UNIQUE,
    plan_id                   VARCHAR(100) NOT NULL,
    plan_number               VARCHAR(30),
    leg_id                    VARCHAR(100),
    carrier_id                VARCHAR(100) NOT NULL,
    carrier_name              VARCHAR(255),
    vehicle_id                VARCHAR(100),
    vehicle_number            VARCHAR(30),
    driver_id                 VARCHAR(100),
    driver_name               VARCHAR(200),
    shipment_type             VARCHAR(30),
    transport_mode            VARCHAR(20),
    load_type                 VARCHAR(20),
    orig_location_id          VARCHAR(100),
    orig_location_name        VARCHAR(255),
    orig_location_type        VARCHAR(30),
    orig_org_id               VARCHAR(100),
    orig_street               VARCHAR(255),
    orig_city                 VARCHAR(100),
    orig_state                VARCHAR(100),
    orig_pincode              VARCHAR(20),
    orig_country              VARCHAR(50),
    dest_location_id          VARCHAR(100),
    dest_location_name        VARCHAR(255),
    dest_location_type        VARCHAR(30),
    dest_org_id               VARCHAR(100),
    dest_street               VARCHAR(255),
    dest_city                 VARCHAR(100),
    dest_state                VARCHAR(100),
    dest_pincode              VARCHAR(20),
    dest_country              VARCHAR(50),
    planned_pickup_date_time  TIMESTAMP,
    planned_delivery_date_time TIMESTAMP,
    actual_pickup_date_time   TIMESTAMP,
    actual_delivery_date_time TIMESTAMP,
    total_weight_kg           NUMERIC(12,3),
    total_volume_m3           NUMERIC(12,4),
    total_packages            INT,
    total_distance_km         NUMERIC(10,2),
    freight_cost              NUMERIC(14,2),
    currency                  VARCHAR(5),
    transport_shipment_id     VARCHAR(100),
    status                    VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    notes                     TEXT,
    created_at                TIMESTAMP NOT NULL,
    updated_at                TIMESTAMP
);

CREATE TABLE transport_order_items (
    item_id         VARCHAR(100) PRIMARY KEY,
    to_id           VARCHAR(100) NOT NULL REFERENCES transport_orders(to_id),
    rts_id          VARCHAR(100),
    consignment_id  VARCHAR(100),
    order_number    VARCHAR(100),
    sku_id          VARCHAR(100),
    product_name    VARCHAR(255),
    quantity        NUMERIC(12,3),
    weight_kg       NUMERIC(12,3),
    volume_m3       NUMERIC(12,4),
    packages        INT
);

CREATE INDEX idx_to_plan     ON transport_orders(plan_id);
CREATE INDEX idx_to_carrier  ON transport_orders(carrier_id);
CREATE INDEX idx_to_status   ON transport_orders(status);
CREATE INDEX idx_to_leg      ON transport_orders(leg_id);
CREATE INDEX idx_toi_to      ON transport_order_items(to_id);
