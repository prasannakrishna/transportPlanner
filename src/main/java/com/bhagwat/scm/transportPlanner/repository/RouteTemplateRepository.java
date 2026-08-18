package com.bhagwat.scm.transportPlanner.repository;
import com.bhagwat.scm.transportPlanner.entity.RouteTemplate;
import com.bhagwat.scm.transportPlanner.enums.ShipmentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface RouteTemplateRepository extends JpaRepository<RouteTemplate, String> {
    List<RouteTemplate> findByIsActiveTrue();
    List<RouteTemplate> findByShipmentType(ShipmentType shipmentType);
    List<RouteTemplate> findByOriginCityAndDestinationCity(String originCity, String destinationCity);
    List<RouteTemplate> findByOriginPincodeAndDestinationPincodeAndIsActiveTrue(String originPincode, String destinationPincode);
    List<RouteTemplate> findByOriginCityAndDestinationCityAndIsActiveTrue(String originCity, String destinationCity);
}
