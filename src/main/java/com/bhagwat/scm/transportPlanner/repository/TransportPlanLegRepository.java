package com.bhagwat.scm.transportPlanner.repository;
import com.bhagwat.scm.transportPlanner.entity.TransportPlanLeg;
import com.bhagwat.scm.transportPlanner.enums.LegStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface TransportPlanLegRepository extends JpaRepository<TransportPlanLeg, String> {
    List<TransportPlanLeg> findByPlanIdOrderByLegSequenceAsc(String planId);
    List<TransportPlanLeg> findByCarrierId(String carrierId);
    List<TransportPlanLeg> findByPlanIdAndStatus(String planId, LegStatus status);
}
