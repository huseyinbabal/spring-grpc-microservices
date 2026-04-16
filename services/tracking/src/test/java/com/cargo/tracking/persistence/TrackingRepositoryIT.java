package com.cargo.tracking.persistence;

import com.cargo.tracking.domain.ShipmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TrackingRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tracking")
                    .withUsername("tracking")
                    .withPassword("tracking");

    @Autowired TrackingEventRepository events;
    @Autowired ShipmentReadModelRepository readModel;

    @BeforeEach
    void reset() {
        events.deleteAll();
        readModel.deleteAll();
    }

    @Test
    void tracking_events_save_and_list_most_recent_first() {
        UUID shipmentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");

        events.save(new TrackingEventEntity(UUID.randomUUID(), shipmentId, 52.52, 13.40, base, "driver-1"));
        events.save(new TrackingEventEntity(UUID.randomUUID(), shipmentId, 52.60, 13.30, base.plus(1, ChronoUnit.HOURS), "driver-1"));
        events.save(new TrackingEventEntity(UUID.randomUUID(), shipmentId, 52.70, 13.20, base.plus(2, ChronoUnit.HOURS), "driver-1"));

        List<TrackingEventEntity> recent =
                events.findByShipmentIdOrderByRecordedAtDesc(shipmentId, PageRequest.of(0, 10));
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).getLat()).isEqualTo(52.70);
        assertThat(recent.get(2).getLat()).isEqualTo(52.52);

        assertThat(events.findFirstByShipmentIdOrderByRecordedAtDesc(shipmentId))
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.getLat()).isEqualTo(52.70));
    }

    @Test
    void report_location_is_idempotent_on_event_id() {
        UUID shipmentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-01T10:00:00Z");

        events.save(new TrackingEventEntity(eventId, shipmentId, 52.52, 13.40, now, "driver-1"));
        // Same PK replaces, doesn't duplicate.
        events.save(new TrackingEventEntity(eventId, shipmentId, 52.60, 13.30, now, "driver-1"));

        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void shipment_read_model_round_trips_all_columns() {
        UUID shipmentId = UUID.randomUUID();
        ShipmentReadModelEntity entity =
                new ShipmentReadModelEntity(shipmentId, "CT-2026-000001", "DHL", ShipmentStatus.CREATED);
        entity.setLastLat(52.52);
        entity.setLastLng(13.40);
        entity.setLastUpdateAt(Instant.parse("2026-03-01T10:00:00Z"));
        readModel.save(entity);

        ShipmentReadModelEntity loaded = readModel.findById(shipmentId).orElseThrow();
        assertThat(loaded.getTrackingCode()).isEqualTo("CT-2026-000001");
        assertThat(loaded.getCarrier()).isEqualTo("DHL");
        assertThat(loaded.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(loaded.getLastLat()).isEqualTo(52.52);
        assertThat(loaded.getLastLng()).isEqualTo(13.40);
        assertThat(loaded.getLastUpdateAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }
}
