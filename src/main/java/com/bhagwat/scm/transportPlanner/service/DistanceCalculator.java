package com.bhagwat.scm.transportPlanner.service;

import com.bhagwat.scm.transportPlanner.entity.RouteTemplate;
import com.bhagwat.scm.transportPlanner.repository.RouteTemplateRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculates distance and ETA between two locations.
 *
 * Strategy:
 *   1. Lookup RouteTemplate by pincode (exact match)
 *   2. Fallback to RouteTemplate by city
 *   3. Fallback to haversine estimation × road factor
 *
 * Used by:
 *   - TransportPlanService (populate leg distances on plan creation)
 *   - PlanTypeCostEstimator (cost calculation per plan type)
 *   - PlanAmendmentService (cost redistribution on consolidation)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DistanceCalculator {

    private final RouteTemplateRepository routeTemplateRepo;

    @Value("${transport.distance.road-factor:1.3}")
    private double roadFactor;

    @Value("${transport.distance.fallback-method:HAVERSINE}")
    private String fallbackMethod;

    // Simple in-memory cache for pincode → approximate lat/lng (India major pincodes)
    private static final Map<String, double[]> PINCODE_COORDS = new ConcurrentHashMap<>();

    static {
        // Major Indian city pincodes (first 3 digits = cluster)
        PINCODE_COORDS.put("560", new double[]{12.9716, 77.5946});  // Bangalore
        PINCODE_COORDS.put("400", new double[]{19.0760, 72.8777});  // Mumbai
        PINCODE_COORDS.put("600", new double[]{13.0827, 80.2707});  // Chennai
        PINCODE_COORDS.put("500", new double[]{17.3850, 78.4867});  // Hyderabad
        PINCODE_COORDS.put("110", new double[]{28.6139, 77.2090});  // Delhi
        PINCODE_COORDS.put("700", new double[]{22.5726, 88.3639});  // Kolkata
        PINCODE_COORDS.put("380", new double[]{23.0225, 72.5714});  // Ahmedabad
        PINCODE_COORDS.put("411", new double[]{18.5204, 73.8567});  // Pune
        PINCODE_COORDS.put("302", new double[]{26.9124, 75.7873});  // Jaipur
        PINCODE_COORDS.put("580", new double[]{15.3647, 75.1240});  // Hubli
        PINCODE_COORDS.put("581", new double[]{14.8500, 74.8500});  // Sirsi
        PINCODE_COORDS.put("570", new double[]{12.2958, 76.6394});  // Mysore
        PINCODE_COORDS.put("590", new double[]{15.8497, 74.4977});  // Belgaum
    }

    /**
     * Calculate distance and ETA between two pincodes.
     * Returns RouteMetrics with source indicating how the distance was derived.
     */
    public RouteMetrics calculate(String originPincode, String destPincode) {
        if (originPincode == null || destPincode == null) {
            return RouteMetrics.builder()
                    .distanceKm(BigDecimal.ZERO)
                    .estimatedTransitHours(BigDecimal.ZERO)
                    .source("UNKNOWN")
                    .build();
        }

        // Strategy 1: Exact pincode match from RouteTemplate
        List<RouteTemplate> exactMatch = routeTemplateRepo
                .findByOriginPincodeAndDestinationPincodeAndIsActiveTrue(originPincode, destPincode);
        if (!exactMatch.isEmpty()) {
            RouteTemplate rt = exactMatch.get(0);
            return RouteMetrics.builder()
                    .distanceKm(rt.getDistanceKm())
                    .estimatedTransitHours(rt.getEstimatedTransitHours())
                    .source("ROUTE_TEMPLATE_PINCODE")
                    .build();
        }

        // Strategy 2: City-level match (use pincode prefix as cluster → city lookup)
        String originCity = pincodeToCity(originPincode);
        String destCity = pincodeToCity(destPincode);
        if (originCity != null && destCity != null) {
            List<RouteTemplate> cityMatch = routeTemplateRepo
                    .findByOriginCityAndDestinationCityAndIsActiveTrue(originCity, destCity);
            if (!cityMatch.isEmpty()) {
                RouteTemplate rt = cityMatch.get(0);
                return RouteMetrics.builder()
                        .distanceKm(rt.getDistanceKm())
                        .estimatedTransitHours(rt.getEstimatedTransitHours())
                        .source("ROUTE_TEMPLATE_CITY")
                        .build();
            }
        }

        // Strategy 3: Haversine fallback
        BigDecimal distance = estimateDistance(originPincode, destPincode);
        BigDecimal transitHours = estimateTransitHours(distance);

        log.debug("No RouteTemplate for {} → {}. Estimated: {}km, {}h",
                originPincode, destPincode, distance, transitHours);

        return RouteMetrics.builder()
                .distanceKm(distance)
                .estimatedTransitHours(transitHours)
                .source("ESTIMATED_HAVERSINE")
                .build();
    }

    /**
     * Calculate distance between two cities (by name).
     */
    public RouteMetrics calculateByCity(String originCity, String destCity) {
        if (originCity == null || destCity == null) {
            return RouteMetrics.builder().distanceKm(BigDecimal.ZERO)
                    .estimatedTransitHours(BigDecimal.ZERO).source("UNKNOWN").build();
        }

        List<RouteTemplate> match = routeTemplateRepo
                .findByOriginCityAndDestinationCityAndIsActiveTrue(originCity, destCity);
        if (!match.isEmpty()) {
            RouteTemplate rt = match.get(0);
            return RouteMetrics.builder()
                    .distanceKm(rt.getDistanceKm())
                    .estimatedTransitHours(rt.getEstimatedTransitHours())
                    .source("ROUTE_TEMPLATE_CITY")
                    .build();
        }

        // No template — return zero (can't estimate without coordinates)
        return RouteMetrics.builder()
                .distanceKm(BigDecimal.valueOf(500)) // default 500km
                .estimatedTransitHours(BigDecimal.valueOf(12))
                .source("DEFAULT_ESTIMATE")
                .build();
    }

    // ── Haversine estimation ─────────────────────────────────────────────────

    private BigDecimal estimateDistance(String originPincode, String destPincode) {
        String originCluster = originPincode.length() >= 3 ? originPincode.substring(0, 3) : originPincode;
        String destCluster = destPincode.length() >= 3 ? destPincode.substring(0, 3) : destPincode;

        double[] originCoords = PINCODE_COORDS.getOrDefault(originCluster, new double[]{12.97, 77.59});
        double[] destCoords = PINCODE_COORDS.getOrDefault(destCluster, new double[]{13.08, 80.27});

        double haversineKm = haversine(originCoords[0], originCoords[1], destCoords[0], destCoords[1]);
        double roadKm = haversineKm * roadFactor;

        return BigDecimal.valueOf(roadKm).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal estimateTransitHours(BigDecimal distanceKm) {
        // Average road speed: 40 km/h for trucks (including rest stops)
        double hours = distanceKm.doubleValue() / 40.0;
        return BigDecimal.valueOf(hours).setScale(2, RoundingMode.HALF_UP);
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private String pincodeToCity(String pincode) {
        if (pincode == null || pincode.length() < 3) return null;
        String cluster = pincode.substring(0, 3);
        return switch (cluster) {
            case "560" -> "Bangalore";
            case "400" -> "Mumbai";
            case "600" -> "Chennai";
            case "500" -> "Hyderabad";
            case "110" -> "Delhi";
            case "700" -> "Kolkata";
            case "380" -> "Ahmedabad";
            case "411" -> "Pune";
            case "302" -> "Jaipur";
            case "580" -> "Hubli";
            case "581" -> "Sirsi";
            case "570" -> "Mysore";
            case "590" -> "Belgaum";
            default -> null;
        };
    }

    // ── Result DTO ───────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class RouteMetrics {
        private BigDecimal distanceKm;
        private BigDecimal estimatedTransitHours;
        /** ROUTE_TEMPLATE_PINCODE, ROUTE_TEMPLATE_CITY, ESTIMATED_HAVERSINE, DEFAULT_ESTIMATE */
        private String source;
    }
}
