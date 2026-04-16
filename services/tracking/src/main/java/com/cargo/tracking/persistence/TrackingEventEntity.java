package com.cargo.tracking.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tracking_events")
public class TrackingEventEntity {

    @Id
    private UUID id;

    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TrackingEventEntity() {}

    public TrackingEventEntity(
            UUID id,
            UUID shipmentId,
            double lat,
            double lng,
            Instant recordedAt,
            String source) {
        this.id = id;
        this.shipmentId = shipmentId;
        this.lat = lat;
        this.lng = lng;
        this.recordedAt = recordedAt;
        this.source = source == null ? "" : source;
    }

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (source == null) {
            source = "";
        }
    }

    public UUID getId() { return id; }
    public UUID getShipmentId() { return shipmentId; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
