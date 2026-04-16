package com.cargo.tracking.domain;

/**
 * Tracking-side mirror of the shipment lifecycle. Kept as a plain
 * Java enum (rather than reusing the proto-generated
 * cargo.shipment.v1.ShipmentStatus) so the persistence layer isn't
 * coupled to the gRPC stubs, and so the read-model column values
 * read naturally (e.g. `CREATED`, not `SHIPMENT_STATUS_CREATED`).
 */
public enum ShipmentStatus {
    CREATED,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED
}
