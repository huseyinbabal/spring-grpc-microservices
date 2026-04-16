package com.cargo.notification.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ShipmentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventsListener.class);

    private final ConcurrentMap<String, Instant> lastSeen = new ConcurrentHashMap<>();

    @KafkaListener(topics = "cargo.shipment.events", groupId = "notification-shipment-events")
    public void onShipmentEvent(
            @Payload String payload,
            @Header(name = "event-type", required = false) byte[] eventTypeHeader,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        String eventType = eventTypeHeader == null ? "unknown" : new String(eventTypeHeader, StandardCharsets.UTF_8);
        log.info("NOTIFY shipment.{} key={}", eventType, key);
        if (key != null) {
            lastSeen.put(key, Instant.now());
        }
    }

    public ConcurrentMap<String, Instant> getLastSeen() {
        return lastSeen;
    }
}
