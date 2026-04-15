package com.cargo.shipment.domain;

import com.cargo.common.grpc.error.NotFoundException;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.persistence.ShipmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Domain service for Shipment lifecycle operations. Knows the state
 * machine, the tracking-code format, and the invariants that must hold
 * on every transition. Knows nothing about gRPC or protobuf.
 */
@Service
public class ShipmentService {

    private final ShipmentRepository repo;
    private final Clock clock;

    @Autowired
    public ShipmentService(ShipmentRepository repo) {
        this(repo, Clock.system(ZoneOffset.UTC));
    }

    ShipmentService(ShipmentRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * Create a new shipment, persist it, and return the stored entity.
     * Validates that carrier and weight are present / non-negative;
     * addresses are validated by {@link Address}'s compact constructor.
     *
     * @throws IllegalArgumentException if any input fails validation
     */
    @Transactional
    public ShipmentEntity createShipment(
            Address origin, Address destination, String carrier, double weightKg) {
        if (origin == null) throw new IllegalArgumentException("origin is required");
        if (destination == null) throw new IllegalArgumentException("destination is required");
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("carrier is required");
        }
        if (weightKg <= 0.0) {
            throw new IllegalArgumentException("weight_kg must be positive, got " + weightKg);
        }

        ShipmentEntity entity = new ShipmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setTrackingCode(generateTrackingCode());
        entity.setOriginLine1(origin.line1());
        entity.setOriginCity(origin.city());
        entity.setOriginCountry(origin.country());
        entity.setOriginPostalCode(origin.postalCode());
        entity.setDestinationLine1(destination.line1());
        entity.setDestinationCity(destination.city());
        entity.setDestinationCountry(destination.country());
        entity.setDestinationPostalCode(destination.postalCode());
        entity.setCarrier(carrier);
        entity.setStatus(ShipmentStatus.CREATED);
        entity.setWeightKg(weightKg);
        return repo.save(entity);
    }

    /**
     * Look up a shipment by its server-assigned UUID.
     *
     * @throws NotFoundException if no shipment with this id exists
     */
    @Transactional(readOnly = true)
    public ShipmentEntity findShipment(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException("shipment " + id + " not found"));
    }

    /**
     * Look up a shipment by its human-friendly tracking code.
     *
     * @throws NotFoundException if no shipment with this tracking code exists
     */
    @Transactional(readOnly = true)
    public ShipmentEntity findShipmentByTrackingCode(String trackingCode) {
        if (trackingCode == null || trackingCode.isBlank()) {
            throw new IllegalArgumentException("tracking_code is required");
        }
        return repo.findByTrackingCode(trackingCode)
                .orElseThrow(() -> new NotFoundException(
                        "shipment with tracking_code " + trackingCode + " not found"));
    }

    private String generateTrackingCode() {
        int year = Year.now(clock).getValue();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "CT-" + year + "-" + suffix;
    }
}
