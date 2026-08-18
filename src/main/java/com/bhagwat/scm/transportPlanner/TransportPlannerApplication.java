package com.bhagwat.scm.transportPlanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.bhagwat.scm.observability.annotation.EnableObservability;

@SpringBootApplication
@EnableObservability
public class TransportPlannerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TransportPlannerApplication.class, args);
	}
}
