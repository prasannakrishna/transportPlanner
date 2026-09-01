package com.bhagwat.scm.transportPlanner.repository;
import com.bhagwat.scm.transportPlanner.entity.TransportPlan;
import com.bhagwat.scm.transportPlanner.enums.TransportPlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
@Repository
public interface TransportPlanRepository extends JpaRepository<TransportPlan, String> {
    Optional<TransportPlan> findByPlanNumber(String planNumber);
    List<TransportPlan> findByCarrierId(String carrierId);
    List<TransportPlan> findByRtsId(String rtsId);
    List<TransportPlan> findByStatus(TransportPlanStatus status);
    List<TransportPlan> findByCarrierIdAndStatus(String carrierId, TransportPlanStatus status);
    List<TransportPlan> findByStatusInAndPlannedStartDateTimeBefore(List<TransportPlanStatus> statuses, LocalDateTime cutoff);
}
