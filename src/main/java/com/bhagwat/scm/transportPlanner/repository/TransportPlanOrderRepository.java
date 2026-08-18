package com.bhagwat.scm.transportPlanner.repository;
import com.bhagwat.scm.transportPlanner.entity.TransportPlanOrder;
import com.bhagwat.scm.transportPlanner.enums.PlanOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface TransportPlanOrderRepository extends JpaRepository<TransportPlanOrder, String> {
    List<TransportPlanOrder> findByPlanId(String planId);
    List<TransportPlanOrder> findByStatus(PlanOrderStatus status);
}
