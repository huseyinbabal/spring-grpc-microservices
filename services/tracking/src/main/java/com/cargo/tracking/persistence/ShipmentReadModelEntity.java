package com.cargo.tracking.persistence;

import com.cargo.tracking.domain.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shipment_read_model")
public class ShipmentReadModelEntity {

    @Id
    @Column(name = "shipment_id")
    private UUID shipmentId;

    @Column(name = "tracking_code")
    private String trackingCode;

    @Column
    private String carrier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(name = "last_lat")
    private Double lastLat;

    @Column(name = "last_lng")
    private Double lastLng;

    @Column(name = "last_update_at")
    private Instant lastUpdateAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ShipmentReadModelEntity() {}

    public ShipmentReadModelEntity(
            UUID shipmentId,
            String trackingCode,
            String carrier,
            ShipmentStatus status) {
        this.shipmentId = shipmentId;
        this.trackingCode = trackingCode;
        this.carrier = carrier;
        this.status = status;
    }

    @PrePersist
    void onInsert() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getShipmentId() { return shipmentId; }
    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }
    public ShipmentStatus getStatus() { return status; }
    public void setStatus(ShipmentStatus status) { this.status = status; }
    public Double getLastLat() { return lastLat; }
    public void setLastLat(Double lastLat) { this.lastLat = lastLat; }
    public Double getLastLng() { return lastLng; }
    public void setLastLng(Double lastLng) { this.lastLng = lastLng; }
    public Instant getLastUpdateAt() { return lastUpdateAt; }
    public void setLastUpdateAt(Instant lastUpdateAt) { this.lastUpdateAt = lastUpdateAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
