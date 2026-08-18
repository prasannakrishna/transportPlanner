package com.bhagwat.scm.transportPlanner.client;

import com.bhagwat.scm.core.rest.api.ApiClient;
import com.bhagwat.scm.core.rest.config.ServiceApiRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * REST client to carrierNetworkService for multi-carrier route resolution.
 *
 * Calls: GET /api/v1/networks/resolve?origin={pincode}&destination={pincode}
 *
 * Used by TransportPlanService.planCrossdock() to assign different carriers
 * to different legs of a CROSSDOCK plan based on carrier network coverage.
 *
 * When no single carrier covers the full route (origin → destination),
 * the carrier network resolves it into multiple legs via cross-dock hubs,
 * each served by a different network member carrier.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CarrierNetworkClient {

    private final ApiClient apiClient;
    private final ServiceApiRegistry registry;

    /**
     * Resolve a multi-carrier route through the carrier network.
     *
     * @param originPincode  shipment origin pincode
     * @param destPincode    shipment destination pincode
     * @return resolved route with per-leg carrier assignments, or empty if no route found
     */
    public Optional<NetworkRouteResolution> resolveRoute(String originPincode, String destPincode) {
        if (originPincode == null || destPincode == null) {
            return Optional.empty();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = apiClient.invoke(
                    registry.getConfig("carrier-network-resolve", originPincode, destPincode),
                    Map.class).getBody();

            if (response == null || response.get("legs") == null) {
                log.info("No network route found for {} → {}", originPincode, destPincode);
                return Optional.empty();
            }

            return Optional.of(parseResolution(response));

        } catch (Exception e) {
            log.warn("CarrierNetworkService unreachable for route {} → {}: {}",
                    originPincode, destPincode, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private NetworkRouteResolution parseResolution(Map<String, Object> response) {
        String routeId = (String) response.get("routeId");
        String routeName = (String) response.get("routeName");
        Integer transitDays = response.get("estimatedTransitDays") != null
                ? ((Number) response.get("estimatedTransitDays")).intValue() : null;

        List<Map<String, Object>> legMaps = (List<Map<String, Object>>) response.get("legs");
        List<NetworkRouteLeg> legs = new ArrayList<>();

        if (legMaps != null) {
            for (Map<String, Object> legMap : legMaps) {
                legs.add(NetworkRouteLeg.builder()
                        .legOrder(legMap.get("legOrder") != null ? ((Number) legMap.get("legOrder")).intValue() : legs.size() + 1)
                        .carrierId((String) legMap.get("carrierId"))
                        .carrierName((String) legMap.get("carrierName"))
                        .fromPincode((String) legMap.get("fromPincode"))
                        .fromCity((String) legMap.get("fromCity"))
                        .toPincode((String) legMap.get("toPincode"))
                        .toCity((String) legMap.get("toCity"))
                        .crossDockId((String) legMap.get("crossDockId"))
                        .estimatedDays(legMap.get("estimatedDays") != null
                                ? ((Number) legMap.get("estimatedDays")).intValue() : 1)
                        .build());
            }
        }

        return NetworkRouteResolution.builder()
                .routeId(routeId)
                .routeName(routeName)
                .estimatedTransitDays(transitDays)
                .legs(legs)
                .build();
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @Data
    @Builder
    public static class NetworkRouteResolution {
        private String routeId;
        private String routeName;
        private Integer estimatedTransitDays;
        private List<NetworkRouteLeg> legs;

        public boolean isMultiCarrier() {
            if (legs == null || legs.size() <= 1) return false;
            Set<String> carriers = new HashSet<>();
            legs.forEach(l -> { if (l.getCarrierId() != null) carriers.add(l.getCarrierId()); });
            return carriers.size() > 1;
        }
    }

    @Data
    @Builder
    public static class NetworkRouteLeg {
        private int legOrder;
        private String carrierId;
        private String carrierName;
        private String fromPincode;
        private String fromCity;
        private String toPincode;
        private String toCity;
        private String crossDockId;
        private int estimatedDays;
    }
}
