package com.bhagwat.scm.transportPlanner.client;

import com.bhagwat.scm.core.rest.api.ApiClient;
import com.bhagwat.scm.core.rest.config.ServiceApiRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST client for contractManager freight rate lookup.
 *
 * Calls: GET /api/v1/contracts/freight-rate?carrierId=X&originZone=Y&destZone=Z&weight=W
 * Falls back to: GET /api/v1/contracts/rate?partyId=X&partyType=CARRIER&contractType=FREIGHT&chargeType=FREIGHT
 * Last resort: configured default rate per km
 *
 * Includes in-memory cache (per planning cycle) to avoid repeated calls
 * for the same lane during batch planning.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FreightRateClient {

    private final ApiClient apiClient;
    private final ServiceApiRegistry registry;

    @Value("${transport.orchestrator.default-rate-per-km:2.10}")
    private double defaultRatePerKm;

    @Value("${transport.orchestrator.rate-cache-ttl-minutes:5}")
    private int cacheTtlMinutes;

    // Simple cache: "carrierId|originPin|destPin" → rate
    private final ConcurrentHashMap<String, CachedRate> rateCache = new ConcurrentHashMap<>();

    /**
     * Get lane-specific freight rate from contractManager.
     *
     * @param carrierId   carrier party ID
     * @param originPincode  origin pincode (or first 3 digits as zone)
     * @param destPincode    destination pincode (or first 3 digits as zone)
     * @param weightKg    shipment weight (for weight-slab matching)
     * @return rate per km, or default if unavailable
     */
    public BigDecimal getFreightRate(String carrierId, String originPincode, String destPincode, BigDecimal weightKg) {
        if (carrierId == null) return BigDecimal.valueOf(defaultRatePerKm);

        String originZone = originPincode != null && originPincode.length() >= 3
                ? originPincode.substring(0, 3) : "000";
        String destZone = destPincode != null && destPincode.length() >= 3
                ? destPincode.substring(0, 3) : "000";

        // Check cache
        String cacheKey = carrierId + "|" + originZone + "|" + destZone;
        CachedRate cached = rateCache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
            return cached.rate;
        }

        // Call contractManager: lane-specific rate
        try {
            String url = String.format(
                    "contract-freight-rate?carrierId=%s&originZone=%s&destZone=%s&weight=%s",
                    carrierId, originZone, destZone,
                    weightKg != null ? weightKg.toPlainString() : "100");

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = apiClient.invoke(registry.getConfig("contract-freight-rate",
                    carrierId, originZone, destZone, weightKg != null ? weightKg.toPlainString() : "100"), Map.class).getBody();

            if (resp != null && resp.get("rate") != null) {
                BigDecimal rate = new BigDecimal(resp.get("rate").toString());
                rateCache.put(cacheKey, new CachedRate(rate));
                log.debug("Freight rate from contract: carrier={} lane={}→{} rate={}/km",
                        carrierId, originZone, destZone, rate);
                return rate;
            }
        } catch (Exception e) {
            log.warn("Failed to get lane-specific rate for carrier={} {}→{}: {}. Trying default rate.",
                    carrierId, originZone, destZone, e.getMessage());
        }

        // Fallback: carrier-level default rate
        BigDecimal defaultRate = getDefaultRate(carrierId);
        rateCache.put(cacheKey, new CachedRate(defaultRate));
        return defaultRate;
    }

    /**
     * Get carrier-level default freight rate (not lane-specific).
     * Calls: GET /api/v1/contracts/rate?partyId=X&partyType=CARRIER&contractType=FREIGHT&chargeType=FREIGHT
     */
    public BigDecimal getDefaultRate(String carrierId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = apiClient.invoke(registry.getConfig("contract-rate-lookup",
                    carrierId, "CARRIER", "FREIGHT", "FREIGHT"), Map.class).getBody();

            if (resp != null && resp.get("rate") != null) {
                BigDecimal rate = new BigDecimal(resp.get("rate").toString());
                log.debug("Default freight rate for carrier {}: {}/km", carrierId, rate);
                return rate;
            }
        } catch (Exception e) {
            log.warn("Failed to get default rate for carrier={}: {}. Using hardcoded default.",
                    carrierId, e.getMessage());
        }

        return BigDecimal.valueOf(defaultRatePerKm);
    }

    /**
     * Calculate total freight cost for a leg.
     *
     * @param carrierId    carrier
     * @param originPincode  origin
     * @param destPincode    destination
     * @param distanceKm     leg distance
     * @param weightKg       cargo weight (for slab lookup)
     * @return estimated freight cost = rate × distance
     */
    public BigDecimal calculateLegCost(String carrierId, String originPincode, String destPincode,
                                        BigDecimal distanceKm, BigDecimal weightKg) {
        BigDecimal rate = getFreightRate(carrierId, originPincode, destPincode, weightKg);
        if (distanceKm == null || distanceKm.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return rate.multiply(distanceKm).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * Clear the rate cache (called at start of each planning cycle).
     */
    public void clearCache() {
        rateCache.clear();
    }

    // ── Cache entry ──────────────────────────────────────────────────────────

    private static class CachedRate {
        final BigDecimal rate;
        final long cachedAt;

        CachedRate(BigDecimal rate) {
            this.rate = rate;
            this.cachedAt = System.currentTimeMillis();
        }

        boolean isExpired(int ttlMinutes) {
            return (System.currentTimeMillis() - cachedAt) > (ttlMinutes * 60_000L);
        }
    }
}
