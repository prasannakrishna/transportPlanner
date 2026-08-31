package com.bhagwat.scm.transportPlanner.client;

import com.bhagwat.scm.core.rest.api.ApiClient;
import com.bhagwat.scm.core.rest.config.ServiceApiRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Verifies a leg's assigned carrier is actually capable of that leg's role
 * (pickup / delivery) for the pincode involved, via carrierService's
 * CarrierServiceArea data — the "does this carrier do first/mid-leg only,
 * or last-mile too, by pincode" flag that previously existed but was never
 * consulted anywhere in plan/leg building.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LogisticsCapabilityClient {

    private final ApiClient apiClient;
    private final ServiceApiRegistry registry;

    public enum Role { PICKUP, DELIVERY }

    /**
     * @return empty if the carrier is already capable (no change needed) or
     *         if carrierService is unreachable (fail open — don't block plan
     *         creation over a capability check); otherwise a substitute
     *         carrier to use instead, if one exists for that pincode/role.
     */
    public Optional<Substitute> checkCapability(String carrierId, String pincode, Role role) {
        if (carrierId == null || pincode == null) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = apiClient.invoke(
                    registry.getConfig("logistics-verify-capability"),
                    Map.of(),
                    Map.of("carrierId", carrierId, "pincode", pincode, "role", role.name()),
                    null, Map.class).getBody();

            if (response == null || Boolean.TRUE.equals(response.get("capable"))) {
                return Optional.empty();
            }

            String substituteCarrierId = (String) response.get("substituteCarrierId");
            if (substituteCarrierId == null) {
                log.warn("Carrier {} not {}-capable for pincode {}, and no substitute available", carrierId, role, pincode);
                return Optional.empty();
            }

            return Optional.of(Substitute.builder()
                    .carrierId(substituteCarrierId)
                    .carrierName((String) response.get("substituteCarrierName"))
                    .build());

        } catch (Exception e) {
            log.warn("Logistics capability check unavailable for carrier {} / pincode {}: {}", carrierId, pincode, e.getMessage());
            return Optional.empty();
        }
    }

    @Data
    @Builder
    public static class Substitute {
        private String carrierId;
        private String carrierName;
    }
}
