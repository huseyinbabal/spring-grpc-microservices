package com.cargo.shipment.outbox;

import com.cargo.shipment.domain.Address;
import com.cargo.shipment.domain.ShipmentService;
import com.cargo.shipment.domain.ShipmentStatus;
import com.cargo.shipment.persistence.OutboxEntity;
import com.cargo.shipment.persistence.OutboxRepository;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.persistence.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that each successful Shipment mutation through
 * {@link ShipmentService} writes exactly one outbox row, and that
 * rejected mutations write zero rows (T3.1's same-transaction
 * guarantee applied end-to-end via the domain service).
 */
@SpringBootTest
@Testcontainers
class ShipmentOutboxIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ShipmentService service;

    @Autowired
    private ShipmentRepository shipmentRepo;

    @Autowired
    private OutboxRepository outboxRepo;

    @BeforeEach
    void cleanup() {
        outboxRepo.deleteAll();
        shipmentRepo.deleteAll();
    }

    @Test
    void create_shipment_writes_one_shipment_created_event() {
        ShipmentEntity shipment = service.createShipment(
                berlin(), paris(), "DHL", 7.5);

        List<OutboxEntity> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        OutboxEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("shipment");
        assertThat(row.getAggregateId()).isEqualTo(shipment.getId().toString());
        assertThat(row.getType()).isEqualTo("shipment.created");
        assertThat(row.getPayload())
                .contains(shipment.getId().toString())
                .contains(shipment.getTrackingCode())
                .contains("DHL")
                .contains("CREATED");
    }

    @Test
    void update_status_writes_one_shipment_status_changed_event() {
        ShipmentEntity shipment = service.createShipment(berlin(), paris(), "DHL", 7.5);
        outboxRepo.deleteAll(); // isolate the status-changed event

        service.updateShipmentStatus(shipment.getId(), ShipmentStatus.IN_TRANSIT);

        List<OutboxEntity> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        OutboxEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("shipment");
        assertThat(row.getAggregateId()).isEqualTo(shipment.getId().toString());
        assertThat(row.getType()).isEqualTo("shipment.status.changed");
        assertThat(row.getPayload())
                .contains("CREATED")
                .contains("IN_TRANSIT");
    }

    @Test
    void cancel_writes_one_shipment_cancelled_event() {
        ShipmentEntity shipment = service.createShipment(berlin(), paris(), "DHL", 7.5);
        outboxRepo.deleteAll();

        service.cancelShipment(shipment.getId());

        List<OutboxEntity> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        OutboxEntity row = rows.get(0);
        assertThat(row.getAggregateType()).isEqualTo("shipment");
        assertThat(row.getAggregateId()).isEqualTo(shipment.getId().toString());
        assertThat(row.getType()).isEqualTo("shipment.cancelled");
        assertThat(row.getPayload()).contains("CREATED"); // previousStatus
    }

    @Test
    void full_lifecycle_emits_three_events_in_order() {
        ShipmentEntity shipment = service.createShipment(berlin(), paris(), "DHL", 7.5);
        service.updateShipmentStatus(shipment.getId(), ShipmentStatus.IN_TRANSIT);
        service.updateShipmentStatus(shipment.getId(), ShipmentStatus.DELIVERED);

        List<OutboxEntity> rows = outboxRepo.findAll().stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getType()).isEqualTo("shipment.created");
        assertThat(rows.get(1).getType()).isEqualTo("shipment.status.changed");
        assertThat(rows.get(2).getType()).isEqualTo("shipment.status.changed");
        // Each row's aggregate_id is the same shipment (partition key
        // guarantee — all 3 events land in the same Kafka partition).
        assertThat(rows).allSatisfy(row ->
                assertThat(row.getAggregateId()).isEqualTo(shipment.getId().toString()));
    }

    @Test
    void failed_create_leaves_no_outbox_row() {
        assertThatThrownBy(() ->
                service.createShipment(berlin(), paris(), /* carrier */ "", 7.5))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(shipmentRepo.count()).isZero();
        assertThat(outboxRepo.count()).isZero();
    }

    @Test
    void rejected_update_leaves_no_new_outbox_row() {
        ShipmentEntity shipment = service.createShipment(berlin(), paris(), "DHL", 7.5);
        long baseline = outboxRepo.count();

        assertThatThrownBy(() ->
                service.updateShipmentStatus(shipment.getId(), ShipmentStatus.DELIVERED))
                .hasMessageContaining("illegal transition");

        assertThat(outboxRepo.count())
                .as("illegal transition must not emit an outbox row")
                .isEqualTo(baseline);
    }

    private static Address berlin() {
        return new Address("Alexanderplatz 1", "Berlin", "DE", "10178");
    }

    private static Address paris() {
        return new Address("Rue de Rivoli 1", "Paris", "FR", "75001");
    }
}
