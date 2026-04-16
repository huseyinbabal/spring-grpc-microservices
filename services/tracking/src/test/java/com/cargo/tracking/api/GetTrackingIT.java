package com.cargo.tracking.api;

import com.cargo.shipment.v1.ShipmentStatus;
import com.cargo.tracking.domain.ShipmentStatus.*;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventRepository;
import com.cargo.tracking.v1.GetTrackingRequest;
import com.cargo.tracking.v1.GetTrackingResponse;
import com.cargo.tracking.v1.ReportLocationRequest;
import com.cargo.tracking.v1.TrackingEvent;
import com.cargo.tracking.v1.TrackingServiceGrpc;
import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Status;
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
        "grpc.server.in-process-name=tracking-get-test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GetTrackingIT {

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
        channel = InProcessChannelBuilder.forName("tracking-get-test")
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
    void returns_snapshot_after_a_report_location_call() {
        UUID shipmentId = UUID.randomUUID();
        Instant recordedAt = Instant.parse("2026-03-01T10:00:00Z");

        client.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.52)
                        .setLng(13.40)
                        .setRecordedAt(Timestamp.newBuilder()
                                .setSeconds(recordedAt.getEpochSecond())
                                .setNanos(recordedAt.getNano())
                                .build())
                        .build())
                .build());

        GetTrackingResponse response = client.getTracking(
                GetTrackingRequest.newBuilder().setShipmentId(shipmentId.toString()).build());

        assertThat(response.getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(response.getStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_CREATED);
        assertThat(response.getLastLat()).isEqualTo(52.52);
        assertThat(response.getLastLng()).isEqualTo(13.40);
        assertThat(response.getLastUpdateAt().getSeconds()).isEqualTo(recordedAt.getEpochSecond());
    }

    @Test
    void returns_snapshot_with_status_from_read_model_even_without_any_ping() {
        UUID shipmentId = UUID.randomUUID();
        readModel.save(new ShipmentReadModelEntity(
                shipmentId, "CT-2026-000001", "DHL",
                com.cargo.tracking.domain.ShipmentStatus.IN_TRANSIT));

        GetTrackingResponse response = client.getTracking(
                GetTrackingRequest.newBuilder().setShipmentId(shipmentId.toString()).build());

        assertThat(response.getStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT);
        assertThat(response.getLastLat()).isZero();
        assertThat(response.getLastLng()).isZero();
        assertThat(response.hasLastUpdateAt()).isFalse();
    }

    @Test
    void get_tracking_for_unknown_shipment_returns_not_found() {
        assertThatThrownBy(() -> client.getTracking(GetTrackingRequest.newBuilder()
                .setShipmentId(UUID.randomUUID().toString())
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.NOT_FOUND.getCode()));
    }

    @Test
    void get_tracking_rejects_non_uuid_shipment_id() {
        assertThatThrownBy(() -> client.getTracking(GetTrackingRequest.newBuilder()
                .setShipmentId("not-a-uuid")
                .build()))
                .isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("shipment_id");
    }
}
