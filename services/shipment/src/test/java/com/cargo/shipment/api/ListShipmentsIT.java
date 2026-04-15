package com.cargo.shipment.api;

import com.cargo.common.v1.Page;
import com.cargo.shipment.domain.ShipmentStatus;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.persistence.ShipmentRepository;
import com.cargo.shipment.v1.ListShipmentsRequest;
import com.cargo.shipment.v1.ListShipmentsResponse;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@code ShipmentService.ListShipments}. Seeds
 * rows directly via the {@link ShipmentRepository} so each test can
 * control status + carrier distribution precisely without depending
 * on UpdateShipmentStatus (which lands in T2.7).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=shipment-test"
})
class ListShipmentsIT {

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
    void empty_db_returns_empty_list_and_blank_next_token() {
        ListShipmentsResponse response = client.listShipments(
                ListShipmentsRequest.newBuilder().build());

        assertThat(response.getShipmentsList()).isEmpty();
        assertThat(response.getPage().getNextToken()).isEmpty();
    }

    @Test
    void page_size_2_over_5_rows_paginates_correctly() {
        seed(5, ShipmentStatus.CREATED, "DHL");

        ListShipmentsResponse first = client.listShipments(
                ListShipmentsRequest.newBuilder()
                        .setPage(Page.newBuilder().setSize(2))
                        .build());
        assertThat(first.getShipmentsList()).hasSize(2);
        assertThat(first.getPage().getNextToken()).isEqualTo("1");

        ListShipmentsResponse second = client.listShipments(
                ListShipmentsRequest.newBuilder()
                        .setPage(Page.newBuilder().setSize(2).setToken("1"))
                        .build());
        assertThat(second.getShipmentsList()).hasSize(2);
        assertThat(second.getPage().getNextToken()).isEqualTo("2");

        ListShipmentsResponse third = client.listShipments(
                ListShipmentsRequest.newBuilder()
                        .setPage(Page.newBuilder().setSize(2).setToken("2"))
                        .build());
        assertThat(third.getShipmentsList()).hasSize(1);
        assertThat(third.getPage().getNextToken()).isEmpty();

        List<String> allIds = List.of(
                first.getShipmentsList().get(0).getId(),
                first.getShipmentsList().get(1).getId(),
                second.getShipmentsList().get(0).getId(),
                second.getShipmentsList().get(1).getId(),
                third.getShipmentsList().get(0).getId());
        assertThat(allIds).doesNotHaveDuplicates();
    }

    @Test
    void filter_by_status_returns_only_matching_rows() {
        seed(3, ShipmentStatus.CREATED, "DHL");
        seed(2, ShipmentStatus.IN_TRANSIT, "DHL");

        ListShipmentsResponse response = client.listShipments(
                ListShipmentsRequest.newBuilder()
                        .setStatusFilter(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                        .build());

        assertThat(response.getShipmentsList()).hasSize(2);
        assertThat(response.getShipmentsList().stream().map(s -> s.getStatus()).collect(Collectors.toSet()))
                .containsExactly(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT);
    }

    @Test
    void filter_by_carrier_returns_only_matching_rows() {
        seed(2, ShipmentStatus.CREATED, "DHL");
        seed(1, ShipmentStatus.CREATED, "FedEx");

        ListShipmentsResponse response = client.listShipments(
                ListShipmentsRequest.newBuilder()
                        .setCarrierFilter("DHL")
                        .build());

        assertThat(response.getShipmentsList()).hasSize(2);
        assertThat(response.getShipmentsList().stream().map(s -> s.getCarrier()).collect(Collectors.toSet()))
                .containsExactly("DHL");
    }

    @Test
    void filter_by_status_and_carrier_combines_predicates() {
        seed(2, ShipmentStatus.CREATED, "DHL");
        seed(1, ShipmentStatus.CREATED, "FedEx");
        seed(2, ShipmentStatus.IN_TRANSIT, "DHL");

        ListShipmentsResponse response = client.listShipments(
                ListShipmentsRequest.newBuilder()
                        .setStatusFilter(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                        .setCarrierFilter("DHL")
                        .build());

        assertThat(response.getShipmentsList()).hasSize(2);
        response.getShipmentsList().forEach(s -> {
            assertThat(s.getStatus()).isEqualTo(com.cargo.shipment.v1.ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT);
            assertThat(s.getCarrier()).isEqualTo("DHL");
        });
    }

    @Test
    void malformed_token_returns_invalid_argument() {
        StatusRuntimeException thrown = assertThrows(() ->
                client.listShipments(ListShipmentsRequest.newBuilder()
                        .setPage(Page.newBuilder().setToken("not-a-number"))
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(thrown.getStatus().getDescription()).contains("integer");
    }

    @Test
    void negative_token_returns_invalid_argument() {
        StatusRuntimeException thrown = assertThrows(() ->
                client.listShipments(ListShipmentsRequest.newBuilder()
                        .setPage(Page.newBuilder().setToken("-1"))
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(thrown.getStatus().getDescription()).contains("non-negative");
    }

    private void seed(int count, ShipmentStatus status, String carrier) {
        for (int i = 0; i < count; i++) {
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
            s.setCarrier(carrier);
            s.setStatus(status);
            s.setWeightKg(5.0);
            repo.saveAndFlush(s);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
