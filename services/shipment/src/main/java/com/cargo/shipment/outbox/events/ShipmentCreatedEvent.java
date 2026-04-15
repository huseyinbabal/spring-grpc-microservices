package com.cargo.shipment.outbox.events;

import java.time.Instant;

/**
 * Outbox event emitted when a new shipment is persisted. Serialized to
 * JSON as the outbox row's payload, routed by Debezium to the
 * {@code cargo.shipment.events} Kafka topic with type
 * {@code shipment.created}.
 */
public record ShipmentCreatedEvent(
        String id,
        String trackingCode,
        String carrier,
        String status,
        Instant createdAt) {
}
