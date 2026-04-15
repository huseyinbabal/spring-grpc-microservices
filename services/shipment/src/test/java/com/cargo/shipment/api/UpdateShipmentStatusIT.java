package com.cargo.shipment.api;

import com.cargo.shipment.domain.ShipmentStatus;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.persistence.ShipmentRepository;
import com.cargo.shipment.v1.Shipment;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
import com.cargo.shipment.v1.UpdateShipmentStatusRequest;
import com.cargo.shipment.v1.UpdateShipmentStatusResponse;
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
 * Integration test for {@code ShipmentService.UpdateShipmentStatus}.
 * Exercises the forward-only state machine (CREATED → IN_TRANSIT →
 * DELIVERED) and all the rejection paths.
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
class UpdateShipmentStatusIT {

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
    void created_to_in_transit_succeeds() {
        ShipmentEntity seed = seed(ShipmentStatus.CREATED);

        UpdateShipmentStatusResponse response = client.updateShipmentStatus(
                UpdateShipmentStatusRequest.newBuilder()
                        .setId(seed.getId().toString())
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                        .build());

        Shipment shipment = response.getShipment();
        assertThat(shipment.getStatus())
                .isEqualTo(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT);
        assertThat(shipment.getId()).isEqualTo(seed.getId().toString());

        ShipmentEntity reloaded = repo.findById(seed.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(reloaded.getUpdatedAt()).isAfter(seed.getUpdatedAt());
    }

    @Test
    void in_transit_to_delivered_succeeds() {
        ShipmentEntity seed = seed(ShipmentStatus.IN_TRANSIT);

        UpdateShipmentStatusResponse response = client.updateShipmentStatus(
                UpdateShipmentStatusRequest.newBuilder()
                        .setId(seed.getId().toString())
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_DELIVERED)
                        .build());

        assertThat(response.getShipment().getStatus())
                .isEqualTo(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_DELIVERED);
        assertThat(repo.findById(seed.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void created_to_delivered_returns_failed_precondition() {
        ShipmentEntity seed = seed(ShipmentStatus.CREATED);

        StatusRuntimeException thrown = assertThrows(() ->
                client.updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                        .setId(seed.getId().toString())
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_DELIVERED)
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(thrown.getStatus().getDescription()).contains("CREATED").contains("DELIVERED");
        assertThat(repo.findById(seed.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    void delivered_to_in_transit_returns_failed_precondition() {
        ShipmentEntity seed = seed(ShipmentStatus.DELIVERED);

        StatusRuntimeException thrown = assertThrows(() ->
                client.updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                        .setId(seed.getId().toString())
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(repo.findById(seed.getId()).orElseThrow().getStatus())
                .isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void cancelled_to_any_returns_failed_precondition() {
        ShipmentEntity seed = seed(ShipmentStatus.CANCELLED);

        StatusRuntimeException thrown = assertThrows(() ->
                client.updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                        .setId(seed.getId().toString())
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void same_status_returns_failed_precondition() {
        ShipmentEntity seed = seed(ShipmentStatus.CREATED);

        StatusRuntimeException thrown = assertThrows(() ->
                client.updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                        .setId(seed.getId().toString())
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_CREATED)
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void unknown_id_returns_not_found() {
        String unknownId = UUID.randomUUID().toString();

        StatusRuntimeException thrown = assertThrows(() ->
                client.updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                        .setId(unknownId)
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(thrown.getStatus().getDescription()).contains(unknownId);
    }

    @Test
    void unspecified_new_status_returns_invalid_argument() {
        ShipmentEntity seed = seed(ShipmentStatus.CREATED);

        StatusRuntimeException thrown = assertThrows(() ->
                client.updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                        .setId(seed.getId().toString())
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_UNSPECIFIED)
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void malformed_uuid_returns_invalid_argument() {
        StatusRuntimeException thrown = assertThrows(() ->
                client.updateShipmentStatus(UpdateShipmentStatusRequest.newBuilder()
                        .setId("not-a-uuid")
                        .setNewStatus(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                        .build()));

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
