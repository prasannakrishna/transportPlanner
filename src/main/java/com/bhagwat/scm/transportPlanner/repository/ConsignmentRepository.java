package com.bhagwat.scm.transportPlanner.repository;
import com.bhagwat.scm.transportPlanner.entity.Consignment;
import com.bhagwat.scm.transportPlanner.enums.ConsignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface ConsignmentRepository extends JpaRepository<Consignment, String> {
    List<Consignment> findByPlanId(String planId);
    List<Consignment> findByStatus(ConsignmentStatus status);
}
