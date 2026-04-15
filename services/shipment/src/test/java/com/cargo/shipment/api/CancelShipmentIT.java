package com.cargo.shipment.api;

import com.cargo.shipment.domain.ShipmentStatus;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.persistence.ShipmentRepository;
import com.cargo.shipment.v1.CancelShipmentRequest;
import com.cargo.shipment.v1.CancelShipmentResponse;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@code ShipmentService.CancelShipment}.
 * Cancel is only legal from {@code CREATED} or {@code IN_TRANSIT};
 * any other starting status is rejected with FAILED_PRECONDITION.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=shipment-test"
})
// See CreateShipmentIT for the reason: release the in-process gRPC
// server name before the next IT boots its own context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CancelShipmentIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ShipmentRepository repo;

    private ManagedChannel channel;
    private ShipmentServiceGrpc.ShipmentServiceBlockingStub client;

    @BeforeEach
    void setup() {
        repo.deleteAll();
        channel = InProcessChannelBuilder.forName("shipment-test")
                .usePlaintext()
                .directExecutor()
                .build();
        client = ShipmentServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdown();
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
        }
    }

    @Test
    void cancel_from_created_succeeds() {
        ShipmentEntity seed = seed(ShipmentStatus.CREATED);

        CancelShipmentResponse response = client.cancelShipment(
                CancelShipmentRequest.newBuilder().setId(seed.getId().toString()).build());

        assertThat(response.getShipment().getStatus())
                .isEqualTo(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_CANCELLED);
        assertThat(repo.findById(seed.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void cancel_from_in_transit_succeeds() {
        ShipmentEntity seed = seed(ShipmentStatus.IN_TRANSIT);

        CancelShipmentResponse response = client.cancelShipment(
                CancelShipmentRequest.newBuilder().setId(seed.getId().toString()).build());

        assertThat(response.getShipment().getStatus())
                .isEqualTo(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_CANCELLED);
        assertThat(repo.findById(seed.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void cancel_from_delivered_returns_failed_precondition() {
        ShipmentEntity seed = seed(ShipmentStatus.DELIVERED);

        StatusRuntimeException thrown = assertThrows(() ->
                client.cancelShipment(CancelShipmentRequest.newBuilder()
                        .setId(seed.getId().toString()).build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(thrown.getStatus().getDescription()).contains("DELIVERED");
        assertThat(repo.findById(seed.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void cancel_from_cancelled_returns_failed_precondition() {
        ShipmentEntity seed = seed(ShipmentStatus.CANCELLED);

        StatusRuntimeException thrown = assertThrows(() ->
                client.cancelShipment(CancelShipmentRequest.newBuilder()
                        .setId(seed.getId().toString()).build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(thrown.getStatus().getDescription()).contains("CANCELLED");
    }

    @Test
    void unknown_id_returns_not_found() {
        String unknownId = UUID.randomUUID().toString();

        StatusRuntimeException thrown = assertThrows(() ->
                client.cancelShipment(CancelShipmentRequest.newBuilder()
                        .setId(unknownId).build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(thrown.getStatus().getDescription()).contains(unknownId);
    }

    @Test
    void malformed_uuid_returns_invalid_argument() {
        StatusRuntimeException thrown = assertThrows(() ->
                client.cancelShipment(CancelShipmentRequest.newBuilder()
                        .setId("not-a-uuid").build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(thrown.getStatus().getDescription()).contains("UUID");
    }

    private ShipmentEntity seed(ShipmentStatus status) {
        ShipmentEntity s = new ShipmentEntity();
        s.setId(UUID.randomUUID());
        s.setTrackingCode("CT-2026-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8).toUpperCase());
        s.setOriginLine1("Alexanderplatz 1");
        s.setOriginCity("Berlin");
        s.setOriginCountry("DE");
        s.setOriginPostalCode("10178");
        s.setDestinationLine1("Rue de Rivoli 1");
        s.setDestinationCity("Paris");
        s.setDestinationCountry("FR");
        s.setDestinationPostalCode("75001");
        s.setCarrier("DHL");
        s.setStatus(status);
        s.setWeightKg(5.0);
        return repo.saveAndFlush(s);
    }

    private static StatusRuntimeException assertThrows(Runnable call) {
        try {
            call.run();
        } catch (StatusRuntimeException e) {
            return e;
        }
        throw new AssertionError("expected StatusRuntimeException but none was thrown");
    }
}
