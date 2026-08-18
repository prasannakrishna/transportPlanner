package com.bhagwat.scm.transportPlanner.client;

import com.bhagwat.scm.core.rest.api.ApiClient;
import com.bhagwat.scm.core.rest.config.ServiceApiRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component @RequiredArgsConstructor @Slf4j
public class ContractManagerClient {

    private final ApiClient apiClient;
    private final ServiceApiRegistry registry;

    public boolean isPartnerNetworkAllowed(String contractId) {
        if (contractId == null || contractId.isBlank()) return false;
        try {
            Map<String, Object> contract = getContract(contractId);
            if (contract == null) return false;
            Map<String, Object> logistics = (Map<String, Object>) contract.get("logisticsTerms");
            return logistics != null && Boolean.TRUE.equals(logistics.get("allowPartnerNetwork"));
        } catch (Exception e) {
            log.error("Failed to check contract {} for partner network: {}", contractId, e.getMessage());
            return false;
        }
    }

    public boolean isWarehouseLogisticsLeverageAllowed(String contractId) {
        if (contractId == null || contractId.isBlank()) return false;
        try {
            Map<String, Object> contract = getContract(contractId);
            if (contract == null) return false;
            Map<String, Object> swTerms = (Map<String, Object>) contract.get("sellerWarehouseTerms");
            if (swTerms == null) return false;
            Map<String, Object> leverages = (Map<String, Object>) swTerms.get("warehouseLeverages");
            return leverages != null && Boolean.TRUE.equals(leverages.get("leverageWarehouseLogisticsContracts"));
        } catch (Exception e) {
            log.error("Failed to check warehouse leverage for contract {}: {}", contractId, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getContract(String contractId) {
        ResponseEntity<Map> resp = apiClient.invoke(registry.getConfig("contract-by-id", contractId), Map.class);
        return resp.getBody();
    }
}
