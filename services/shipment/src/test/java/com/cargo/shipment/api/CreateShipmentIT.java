package com.cargo.shipment.api;

import com.cargo.common.v1.Address;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.persistence.ShipmentRepository;
import com.cargo.shipment.v1.CreateShipmentRequest;
import com.cargo.shipment.v1.CreateShipmentResponse;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
import com.cargo.shipment.v1.ShipmentStatus;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@code ShipmentService.CreateShipment} — exercises
 * the full gRPC → domain → JPA → Postgres slice via an in-process gRPC
 * channel plus a Testcontainers Postgres wired in with
 * {@link ServiceConnection}.
 *
 * <p>See {@link com.cargo.shipment.persistence.ShipmentRepositoryIT} for
 * the note about Docker Desktop 29.2+ incompatibility with Testcontainers
 * and the manual-Postgres fallback used for local verification.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=shipment-test"
})
class CreateShipmentIT {

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
    void create_shipment_persists_row_and_returns_populated_shipment() {
        CreateShipmentRequest request = validRequest().build();

        CreateShipmentResponse response = client.createShipment(request);

        var shipment = response.getShipment();
        assertThat(shipment.getId()).isNotEmpty();
        assertThat(UUID.fromString(shipment.getId())).isNotNull();
        assertThat(shipment.getTrackingCode()).matches("^CT-\\d{4}-[A-F0-9]{8}$");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.SHIPMENT_STATUS_CREATED);
        assertThat(shipment.getCarrier()).isEqualTo("DHL");
        assertThat(shipment.getWeightKg()).isEqualTo(7.25);
        assertThat(shipment.getOrigin().getCity()).isEqualTo("Berlin");
        assertThat(shipment.getDestination().getCity()).isEqualTo("Paris");
        assertThat(shipment.getCreatedAt().getSeconds()).isPositive();
        assertThat(shipment.getUpdatedAt().getSeconds()).isPositive();

        Optional<ShipmentEntity> row = repo.findByTrackingCode(shipment.getTrackingCode());
        assertThat(row).isPresent();
        assertThat(row.get().getId()).isEqualTo(UUID.fromString(shipment.getId()));
        assertThat(row.get().getCarrier()).isEqualTo("DHL");
    }

    @Test
    void two_creates_produce_distinct_ids_and_tracking_codes() {
        CreateShipmentResponse a = client.createShipment(validRequest().build());
        CreateShipmentResponse b = client.createShipment(validRequest().build());

        assertThat(a.getShipment().getId()).isNotEqualTo(b.getShipment().getId());
        assertThat(a.getShipment().getTrackingCode())
                .isNotEqualTo(b.getShipment().getTrackingCode());
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    void missing_carrier_returns_invalid_argument() {
        CreateShipmentRequest request = validRequest().setCarrier("").build();

        assertThatThrownBy(() -> client.createShipment(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThat(repo.count()).isZero();
    }

    @Test
    void zero_weight_returns_invalid_argument() {
        CreateShipmentRequest request = validRequest().setWeightKg(0.0).build();

        assertThatThrownBy(() -> client.createShipment(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThat(repo.count()).isZero();
    }

    @Test
    void blank_origin_line1_returns_invalid_argument() {
        CreateShipmentRequest request = validRequest()
                .setOrigin(Address.newBuilder()
                        .setLine1("")
                        .setCity("Berlin")
                        .setCountry("DE")
                        .setPostalCode("10178")
                        .build())
                .build();

        assertThatThrownBy(() -> client.createShipment(request))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThat(repo.count()).isZero();
    }

    private static CreateShipmentRequest.Builder validRequest() {
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
                .setWeightKg(7.25);
    }
}
