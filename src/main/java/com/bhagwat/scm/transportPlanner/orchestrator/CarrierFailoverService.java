package com.bhagwat.scm.transportPlanner.orchestrator;

import com.bhagwat.scm.transportPlanner.entity.CarrierAvailability;
import com.bhagwat.scm.transportPlanner.entity.TransportOrder;
import com.bhagwat.scm.transportPlanner.enums.TransportOrderStatus;
import com.bhagwat.scm.transportPlanner.kafka.TransportPlanKafkaProducer;
import com.bhagwat.scm.transportPlanner.repository.CarrierAvailabilityRepository;
import com.bhagwat.scm.transportPlanner.repository.TransportOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Monitors Transport Orders for carrier acceptance SLA and reassigns on timeout.
 *
 * When a TO is dispatched to a carrier (status = DISPATCHED_TO_CARRIER), the carrier
 * has a configured SLA window to accept it (CARRIER_ACCEPTED). If they don't respond
 * within that window, this service:
 *   1. Finds the next available carrier
 *   2. Reassigns the TO
 *   3. Publishes a plan.updated event with failover reason
 *   4. Logs the decision for audit
 *
 * Runs every 60 seconds to check for stuck TOs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CarrierFailoverService {

    private final TransportOrderRepository toRepo;
    private final CarrierAvailabilityRepository availabilityRepo;
    private final TransportPlanKafkaProducer kafkaProducer;

    @Value("${transport.orchestrator.carrier-failover-sla-minutes:120}")
    private int failoverSlaMinutes;

    /**
     * Scheduled check: find TOs stuck at DISPATCHED_TO_CARRIER beyond the SLA window.
     */
    @Scheduled(fixedDelay = 60000) // every 60s
    @Transactional
    public void checkFailovers() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(failoverSlaMinutes);

        List<TransportOrder> stuck = toRepo.findByStatus(TransportOrderStatus.DISPATCHED_TO_CARRIER);

        for (TransportOrder to : stuck) {
            if (to.getCreatedAt() != null && to.getCreatedAt().isBefore(cutoff)) {
                log.warn("TO {} stuck at DISPATCHED_TO_CARRIER for >{}min. Triggering failover. carrier={}",
                        to.getToNumber(), failoverSlaMinutes, to.getCarrierId());
                failover(to.getToId(), "Carrier did not accept within " + failoverSlaMinutes + " minutes SLA");
            }
        }
    }

    /**
     * Reassign a Transport Order to the next available carrier.
     */
    @Transactional
    public void failover(String toId, String reason) {
        TransportOrder to = toRepo.findById(toId).orElse(null);
        if (to == null) {
            log.warn("Failover requested for unknown TO: {}", toId);
            return;
        }

        String originalCarrier = to.getCarrierId();

        // Find next available carrier (any carrier with unbooked vehicles)
        List<CarrierAvailability> available = availabilityRepo.findByIsBookedFalse();

        // Exclude the current (failed) carrier
        CarrierAvailability nextCarrier = available.stream()
                .filter(a -> !a.getCarrierId().equals(originalCarrier))
                .filter(a -> a.getCapacityKg() != null
                        && (to.getTotalWeightKg() == null || a.getCapacityKg().compareTo(to.getTotalWeightKg()) >= 0))
                .findFirst()
                .orElse(null);

        if (nextCarrier == null) {
            log.error("Failover FAILED for TO {}: no alternative carrier available. Original carrier: {}",
                    to.getToNumber(), originalCarrier);
            // Mark as FAILED — needs manual intervention
            to.setStatus(TransportOrderStatus.FAILED);
            to.setNotes("FAILOVER_FAILED: " + reason + " | No alternative carrier available");
            toRepo.save(to);
            return;
        }

        // Reassign to new carrier
        String newCarrierId = nextCarrier.getCarrierId();
        String newCarrierName = nextCarrier.getCarrierName();

        to.setCarrierId(newCarrierId);
        to.setCarrierName(newCarrierName);
        to.setStatus(TransportOrderStatus.DISPATCHED_TO_CARRIER); // reset — new carrier needs to accept
        to.setUpdatedAt(LocalDateTime.now());
        to.setNotes("FAILOVER: " + reason + " | Reassigned from " + originalCarrier + " to " + newCarrierId);
        toRepo.save(to);

        // Publish plan.updated event with failover details
        kafkaProducer.publishPlanUpdated(to.getPlanId(), Map.of(
                "event", "CARRIER_FAILOVER",
                "toId", to.getToId(),
                "toNumber", to.getToNumber(),
                "originalCarrierId", originalCarrier,
                "newCarrierId", newCarrierId,
                "newCarrierName", newCarrierName != null ? newCarrierName : "",
                "reason", reason,
                "failoverAt", LocalDateTime.now().toString()
        ));

        // Re-publish TO to new carrier
        kafkaProducer.publishTransportOrderCreated(to);

        log.info("Failover complete: TO {} reassigned from {} to {} (reason: {})",
                to.getToNumber(), originalCarrier, newCarrierId, reason);
    }
}
