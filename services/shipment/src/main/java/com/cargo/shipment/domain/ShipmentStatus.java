package com.cargo.shipment.domain;

/**
 * Domain-side shipment status enum. Kept separate from the proto enum
 * ({@code com.cargo.shipment.v1.ShipmentStatus}) so the persistence
 * layer doesn't leak wire contracts into the database column. The API
 * layer maps between the two in Phase 2a T2.4+.
 */
public enum ShipmentStatus {
    CREATED,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED
}
