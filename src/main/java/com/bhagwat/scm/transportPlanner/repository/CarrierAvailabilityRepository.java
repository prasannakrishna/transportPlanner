package com.bhagwat.scm.transportPlanner.repository;
import com.bhagwat.scm.transportPlanner.entity.CarrierAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface CarrierAvailabilityRepository extends JpaRepository<CarrierAvailability, String> {
    List<CarrierAvailability> findByCarrierId(String carrierId);
    List<CarrierAvailability> findByIsBookedFalse();
    List<CarrierAvailability> findByCarrierIdAndIsBooked(String carrierId, Boolean isBooked);
    List<CarrierAvailability> findByCarrierIdAndIsBookedFalse(String carrierId);
}
