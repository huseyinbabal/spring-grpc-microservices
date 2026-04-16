package com.cargo.tracking.api;

import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventRepository;
import com.cargo.tracking.v1.ReportLocationRequest;
import com.cargo.tracking.v1.ReportLocationResponse;
import com.cargo.tracking.v1.TrackingEvent;
import com.cargo.tracking.v1.TrackingServiceGrpc;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=tracking-test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportLocationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tracking")
                    .withUsername("tracking")
                    .withPassword("tracking");

    @Autowired TrackingEventRepository events;
    @Autowired ShipmentReadModelRepository readModel;

    private ManagedChannel channel;
    private TrackingServiceGrpc.TrackingServiceBlockingStub client;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        readModel.deleteAll();
        channel = InProcessChannelBuilder.forName("tracking-test")
                .usePlaintext()
                .directExecutor()
                .build();
        client = TrackingServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }

    @Test
    void report_location_persists_event_and_populates_last_position() {
        UUID shipmentId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-03-01T10:00:00Z");

        ReportLocationResponse response = client.reportLocation(
                ReportLocationRequest.newBuilder()
                        .setEvent(TrackingEvent.newBuilder()
                                .setShipmentId(shipmentId.toString())
                                .setLat(52.52)
                                .setLng(13.40)
                                .setRecordedAt(toTs(recordedAt))
                                .setSource("driver-1")
                                .build())
                        .build());

        assertThat(response.getId()).isNotBlank();
        UUID id = UUID.fromString(response.getId());
        assertThat(events.findById(id)).isPresent();

        assertThat(readModel.findById(shipmentId))
                .isPresent()
                .get()
                .satisfies(rm -> {
                    assertThat(rm.getLastLat()).isEqualTo(52.52);
                    assertThat(rm.getLastLng()).isEqualTo(13.40);
                    assertThat(rm.getLastUpdateAt()).isEqualTo(recordedAt);
                });
    }

    @Test
    void report_location_is_idempotent_on_client_supplied_id() {
        UUID shipmentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-03-01T10:00:00Z");

        TrackingEvent event = TrackingEvent.newBuilder()
                .setId(eventId.toString())
                .setShipmentId(shipmentId.toString())
                .setLat(52.52)
                .setLng(13.40)
                .setRecordedAt(toTs(recordedAt))
                .build();

        ReportLocationResponse first = client.reportLocation(
                ReportLocationRequest.newBuilder().setEvent(event).build());
        ReportLocationResponse second = client.reportLocation(
                ReportLocationRequest.newBuilder().setEvent(event).build());

        assertThat(first.getId()).isEqualTo(eventId.toString());
        assertThat(second.getId()).isEqualTo(eventId.toString());
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void report_location_does_not_rewind_last_position_on_out_of_order_event() {
        UUID shipmentId = UUID.randomUUID();
        Instant early = Instant.parse("2026-03-01T10:00:00Z");
        Instant later = Instant.parse("2026-03-01T11:00:00Z");

        client.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.70).setLng(13.20)
                        .setRecordedAt(toTs(later))
                        .build())
                .build());

        client.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.52).setLng(13.40)
                        .setRecordedAt(toTs(early))
                        .build())
                .build());

        assertThat(readModel.findById(shipmentId))
                .get()
                .satisfies(rm -> {
                    assertThat(rm.getLastLat()).isEqualTo(52.70);
                    assertThat(rm.getLastUpdateAt()).isEqualTo(later);
                });
        assertThat(events.count()).isEqualTo(2);
    }

    @Test
    void report_location_rejects_missing_event() {
        assertThatThrownBy(() -> client.reportLocation(ReportLocationRequest.newBuilder().build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("event is required");
    }

    @Test
    void report_location_rejects_non_uuid_shipment_id() {
        assertThatThrownBy(() -> client.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId("not-a-uuid")
                        .setLat(0).setLng(0)
                        .build())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("shipment_id");
    }

    private static Timestamp toTs(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
