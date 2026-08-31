package com.bhagwat.scm.transportPlanner.repository;

import com.bhagwat.scm.transportPlanner.entity.TransportOrder;
import com.bhagwat.scm.transportPlanner.enums.TransportOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransportOrderRepository extends JpaRepository<TransportOrder, String> {
    Optional<TransportOrder> findByToNumber(String toNumber);
    List<TransportOrder> findByPlanId(String planId);
    List<TransportOrder> findByCarrierId(String carrierId);
    List<TransportOrder> findByCarrierIdAndStatus(String carrierId, TransportOrderStatus status);
    List<TransportOrder> findByStatus(TransportOrderStatus status);
    Optional<TransportOrder> findByLegId(String legId);
    List<TransportOrder> findAllByLegId(String legId);
    Optional<TransportOrder> findByTransportShipmentId(String transportShipmentId);
}
