package com.bhagwat.scm.transportPlanner.entity;

import com.bhagwat.scm.transportPlanner.enums.LocationType;
import jakarta.persistence.*;
import lombok.*;

@Embeddable
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PlanLocation {
    @Column(length = 100)
    private String locationId;
    @Column(length = 255)
    private String locationName;
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private LocationType locationType;
    @Column(length = 100)
    private String orgId;
    @Column(length = 255)
    private String street;
    @Column(length = 100)
    private String city;
    @Column(length = 100)
    private String state;
    @Column(length = 20)
    private String pincode;
    @Column(length = 50)
    private String country;
}
