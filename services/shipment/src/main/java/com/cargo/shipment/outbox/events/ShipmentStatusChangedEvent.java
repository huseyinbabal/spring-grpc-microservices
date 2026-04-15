package com.cargo.shipment.outbox.events;

import java.time.Instant;

/**
 * Outbox event emitted when a shipment transitions to a new status.
 * Routed by Debezium to {@code cargo.shipment.events} with type
 * {@code shipment.status.changed}.
 */
public record ShipmentStatusChangedEvent(
        String id,
        String trackingCode,
        String previousStatus,
        String newStatus,
        Instant changedAt) {
}
