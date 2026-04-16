package com.cargo.notification.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class StalledCargoDetector {

    private static final Logger log = LoggerFactory.getLogger(StalledCargoDetector.class);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

    private final ShipmentEventsListener listener;

    public StalledCargoDetector(ShipmentEventsListener listener) {
        this.listener = listener;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void detectStalledCargo() {
        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
        for (Map.Entry<String, Instant> entry : listener.getLastSeen().entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                log.info("NOTIFY tracking.stalled id={}", entry.getKey());
            }
        }
    }
}
