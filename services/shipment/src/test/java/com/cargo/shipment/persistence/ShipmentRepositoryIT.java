package com.cargo.shipment.persistence;

import com.cargo.shipment.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link ShipmentRepository} + the V1 Flyway
 * migration + the JPA entity mapping.
 *
 * <p>Uses Testcontainers to boot a throwaway {@code postgres:16-alpine}
 * container and wires it into Spring Boot's {@code DataSource} via
 * {@link ServiceConnection}. On hosts where Testcontainers can't talk to
 * Docker (e.g. Docker Desktop 29.2+ on macOS which rejects docker-java's
 * default API version probe on the unix socket), this test can be run
 * against a manually-started Postgres by temporarily replacing the
 * {@code @ServiceConnection} container with a {@code @TestPropertySource}
 * block pointing at {@code localhost:<port>} — see the commit message
 * for T2.3 for the exact override used to verify locally.
 */
@SpringBootTest
@Testcontainers
class ShipmentRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ShipmentRepository repo;

    @BeforeEach
    void cleanup() {
        repo.deleteAll();
    }

    @Test
    void saves_and_retrieves_shipment_by_id() {
        ShipmentEntity persisted = repo.save(newShipment("CT-2026-000001"));

        ShipmentEntity loaded = repo.findById(persisted.getId()).orElseThrow();

        assertThat(loaded.getTrackingCode()).isEqualTo("CT-2026-000001");
        assertThat(loaded.getCarrier()).isEqualTo("DHL");
        assertThat(loaded.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(loaded.getWeightKg()).isEqualTo(5.5);
        assertThat(loaded.getOriginCity()).isEqualTo("Berlin");
        assertThat(loaded.getDestinationCity()).isEqualTo("Paris");
    }

    @Test
    void finds_by_tracking_code() {
        ShipmentEntity persisted = repo.save(newShipment("CT-2026-000002"));

        Optional<ShipmentEntity> loaded = repo.findByTrackingCode("CT-2026-000002");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo(persisted.getId());
    }

    @Test
    void unknown_tracking_code_returns_empty() {
        assertThat(repo.findByTrackingCode("does-not-exist")).isEmpty();
    }

    @Test
    void tracking_code_unique_constraint_rejects_duplicates() {
        repo.saveAndFlush(newShipment("CT-2026-000003"));

        assertThatThrownBy(() -> repo.saveAndFlush(newShipment("CT-2026-000003")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sets_created_at_and_updated_at_on_persist() {
        Instant before = Instant.now().minusSeconds(1);

        ShipmentEntity persisted = repo.saveAndFlush(newShipment("CT-2026-000004"));

        assertThat(persisted.getCreatedAt()).isAfterOrEqualTo(before);
        assertThat(persisted.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void updates_updated_at_on_modify() throws InterruptedException {
        ShipmentEntity persisted = repo.saveAndFlush(newShipment("CT-2026-000005"));
        Instant originalUpdatedAt = persisted.getUpdatedAt();

        Thread.sleep(10);
        persisted.setStatus(ShipmentStatus.IN_TRANSIT);
        ShipmentEntity updated = repo.saveAndFlush(persisted);

        assertThat(updated.getUpdatedAt()).isAfter(originalUpdatedAt);
        assertThat(updated.getCreatedAt()).isEqualTo(persisted.getCreatedAt());
    }

    private static ShipmentEntity newShipment(String trackingCode) {
        ShipmentEntity s = new ShipmentEntity();
        s.setId(UUID.randomUUID());
        s.setTrackingCode(trackingCode);
        s.setOriginLine1("Alexanderplatz 1");
        s.setOriginCity("Berlin");
        s.setOriginCountry("DE");
        s.setOriginPostalCode("10178");
        s.setDestinationLine1("Rue de Rivoli 1");
        s.setDestinationCity("Paris");
        s.setDestinationCountry("FR");
        s.setDestinationPostalCode("75001");
        s.setCarrier("DHL");
        s.setStatus(ShipmentStatus.CREATED);
        s.setWeightKg(5.5);
        return s;
    }
}
