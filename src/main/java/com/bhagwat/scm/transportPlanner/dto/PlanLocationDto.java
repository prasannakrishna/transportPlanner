package com.bhagwat.scm.transportPlanner.dto;
import com.bhagwat.scm.transportPlanner.enums.LocationType;
import lombok.*;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PlanLocationDto {
    private String locationId;
    private String locationName;
    private LocationType locationType;
    private String orgId;
    private String street;
    private String city;
    private String state;
    private String pincode;
    private String country;
}
