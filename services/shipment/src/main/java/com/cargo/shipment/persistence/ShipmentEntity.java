package com.cargo.shipment.persistence;

import com.cargo.shipment.domain.ShipmentStatus;
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
@Table(name = "shipments")
public class ShipmentEntity {

    @Id
    private UUID id;

    @Column(name = "tracking_code", nullable = false, unique = true)
    private String trackingCode;

    @Column(name = "origin_line1", nullable = false)
    private String originLine1;
    @Column(name = "origin_city", nullable = false)
    private String originCity;
    @Column(name = "origin_country", nullable = false)
    private String originCountry;
    @Column(name = "origin_postal_code", nullable = false)
    private String originPostalCode;

    @Column(name = "destination_line1", nullable = false)
    private String destinationLine1;
    @Column(name = "destination_city", nullable = false)
    private String destinationCity;
    @Column(name = "destination_country", nullable = false)
    private String destinationCountry;
    @Column(name = "destination_postal_code", nullable = false)
    private String destinationPostalCode;

    @Column(nullable = false)
    private String carrier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(name = "weight_kg", nullable = false)
    private double weightKg;

    @Column
    private Instant eta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ShipmentEntity() {}

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }

    public String getOriginLine1() { return originLine1; }
    public void setOriginLine1(String originLine1) { this.originLine1 = originLine1; }
    public String getOriginCity() { return originCity; }
    public void setOriginCity(String originCity) { this.originCity = originCity; }
    public String getOriginCountry() { return originCountry; }
    public void setOriginCountry(String originCountry) { this.originCountry = originCountry; }
    public String getOriginPostalCode() { return originPostalCode; }
    public void setOriginPostalCode(String originPostalCode) { this.originPostalCode = originPostalCode; }

    public String getDestinationLine1() { return destinationLine1; }
    public void setDestinationLine1(String destinationLine1) { this.destinationLine1 = destinationLine1; }
    public String getDestinationCity() { return destinationCity; }
    public void setDestinationCity(String destinationCity) { this.destinationCity = destinationCity; }
    public String getDestinationCountry() { return destinationCountry; }
    public void setDestinationCountry(String destinationCountry) { this.destinationCountry = destinationCountry; }
    public String getDestinationPostalCode() { return destinationPostalCode; }
    public void setDestinationPostalCode(String destinationPostalCode) { this.destinationPostalCode = destinationPostalCode; }

    public String getCarrier() { return carrier; }
    public void setCarrier(String carrier) { this.carrier = carrier; }

    public ShipmentStatus getStatus() { return status; }
    public void setStatus(ShipmentStatus status) { this.status = status; }

    public double getWeightKg() { return weightKg; }
    public void setWeightKg(double weightKg) { this.weightKg = weightKg; }

    public Instant getEta() { return eta; }
    public void setEta(Instant eta) { this.eta = eta; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
