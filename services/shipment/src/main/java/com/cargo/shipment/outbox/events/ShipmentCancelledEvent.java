package com.cargo.shipment.outbox.events;

import java.time.Instant;

/**
 * Outbox event emitted when a shipment is cancelled. Routed by
 * Debezium to {@code cargo.shipment.events} with type
 * {@code shipment.cancelled}.
 */
public record ShipmentCancelledEvent(
        String id,
        String trackingCode,
        String previousStatus,
        Instant cancelledAt) {
}
