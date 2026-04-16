package com.cargo.tracking.events;

import com.cargo.tracking.domain.ShipmentStatus;
import com.cargo.tracking.domain.TrackingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes shipment lifecycle events from {@code cargo.shipment.events}
 * and projects them into {@code shipment_read_model} so GetTracking /
 * StreamTracking can answer without an RPC hop to Shipment.
 *
 * <p>Debezium's Outbox Event Router emits the event type on the
 * {@code event-type} Kafka header
 * (shipment.created / shipment.status.changed / shipment.cancelled).
 * The payload is the outbox row's {@code payload} jsonb column as a
 * JSON string.
 */
@Component
public class ShipmentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventsConsumer.class);

    private final TrackingService tracking;
    private final ObjectMapper mapper;

    public ShipmentEventsConsumer(TrackingService tracking, ObjectMapper mapper) {
        this.tracking = tracking;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${cargo.tracking.shipment-events-topic}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onShipmentEvent(
            @Payload String payload,
            @Header(name = "event-type", required = false) byte[] eventTypeHeader,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        String eventType = eventTypeHeader == null
                ? "unknown"
                : new String(eventTypeHeader);
        try {
            JsonNode json = mapper.readTree(payload);
            UUID shipmentId = readShipmentId(json);
            if (shipmentId == null) {
                log.warn("skipping shipment event with no id (key={}, type={})", key, eventType);
                return;
            }
            String trackingCode = textOrNull(json, "trackingCode");
            String carrier = textOrNull(json, "carrier");

            ShipmentStatus status = switch (eventType) {
                case "shipment.created" -> parseStatus(json, "status", ShipmentStatus.CREATED);
                case "shipment.status.changed" -> parseStatus(json, "newStatus", ShipmentStatus.CREATED);
                case "shipment.cancelled" -> ShipmentStatus.CANCELLED;
                default -> null;
            };
            if (status == null) {
                log.warn("ignoring unrecognised event-type {} for shipment {}", eventType, shipmentId);
                return;
            }
            tracking.applyShipmentEvent(shipmentId, status, trackingCode, carrier);
        } catch (Exception e) {
            // Log and swallow so a single malformed payload can't halt
            // the consumer group. Dead-letter handling is out of scope
            // for v0.1.0.
            log.error("failed to apply shipment event (key={}, type={}): {}", key, eventType, e.toString());
        }
    }

    private static UUID readShipmentId(JsonNode json) {
        String id = textOrNull(json, "id");
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }

    private static ShipmentStatus parseStatus(JsonNode json, String field, ShipmentStatus fallback) {
        String raw = textOrNull(json, field);
        if (raw == null) {
            return fallback;
        }
        try {
            return ShipmentStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
