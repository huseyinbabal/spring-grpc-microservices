package com.cargo.shipment.api;

import com.cargo.common.v1.Address;
import com.cargo.shipment.persistence.ShipmentRepository;
import com.cargo.shipment.v1.CreateShipmentRequest;
import com.cargo.shipment.v1.CreateShipmentResponse;
import com.cargo.shipment.v1.GetShipmentRequest;
import com.cargo.shipment.v1.GetShipmentResponse;
import com.cargo.shipment.v1.Shipment;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@code ShipmentService.GetShipment}. Seeds a
 * shipment via CreateShipment and then exercises both {@code oneof key}
 * branches plus the error cases.
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
class GetShipmentIT {

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
    void returns_shipment_when_found_by_id() {
        Shipment seed = client.createShipment(validCreate()).getShipment();

        GetShipmentResponse response = client.getShipment(
                GetShipmentRequest.newBuilder().setId(seed.getId()).build());

        assertThat(response.getShipment().getId()).isEqualTo(seed.getId());
        assertThat(response.getShipment().getTrackingCode()).isEqualTo(seed.getTrackingCode());
        assertThat(response.getShipment().getCarrier()).isEqualTo("DHL");
    }

    @Test
    void returns_shipment_when_found_by_tracking_code() {
        Shipment seed = client.createShipment(validCreate()).getShipment();

        GetShipmentResponse response = client.getShipment(
                GetShipmentRequest.newBuilder()
                        .setTrackingCode(seed.getTrackingCode())
                        .build());

        assertThat(response.getShipment().getId()).isEqualTo(seed.getId());
        assertThat(response.getShipment().getTrackingCode()).isEqualTo(seed.getTrackingCode());
    }

    @Test
    void unknown_id_returns_not_found() {
        String unknownId = UUID.randomUUID().toString();

        StatusRuntimeException thrown = assertThrows(() ->
                client.getShipment(GetShipmentRequest.newBuilder().setId(unknownId).build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(thrown.getStatus().getDescription()).contains(unknownId);
    }

    @Test
    void unknown_tracking_code_returns_not_found() {
        StatusRuntimeException thrown = assertThrows(() ->
                client.getShipment(GetShipmentRequest.newBuilder()
                        .setTrackingCode("CT-2026-NOPE")
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(thrown.getStatus().getDescription()).contains("CT-2026-NOPE");
    }

    @Test
    void missing_key_returns_invalid_argument() {
        StatusRuntimeException thrown = assertThrows(() ->
                client.getShipment(GetShipmentRequest.newBuilder().build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(thrown.getStatus().getDescription()).contains("must be set");
    }

    @Test
    void malformed_uuid_returns_invalid_argument() {
        StatusRuntimeException thrown = assertThrows(() ->
                client.getShipment(GetShipmentRequest.newBuilder()
                        .setId("not-a-uuid")
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(thrown.getStatus().getDescription()).contains("UUID");
    }

    private static CreateShipmentRequest validCreate() {
        return CreateShipmentRequest.newBuilder()
                .setOrigin(Address.newBuilder()
                        .setLine1("Alexanderplatz 1")
                        .setCity("Berlin")
                        .setCountry("DE")
                        .setPostalCode("10178")
                        .build())
                .setDestination(Address.newBuilder()
                        .setLine1("Rue de Rivoli 1")
                        .setCity("Paris")
                        .setCountry("FR")
                        .setPostalCode("75001")
                        .build())
                .setCarrier("DHL")
                .setWeightKg(7.25)
                .build();
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
