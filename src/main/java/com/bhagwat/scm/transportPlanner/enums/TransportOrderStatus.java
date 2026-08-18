package com.bhagwat.scm.transportPlanner.enums;

public enum TransportOrderStatus {
    CREATED,
    DISPATCHED_TO_CARRIER,
    CARRIER_ACCEPTED,
    VEHICLE_ASSIGNED,
    IN_EXECUTION,
    COMPLETED,
    FAILED,
    CANCELLED
}
