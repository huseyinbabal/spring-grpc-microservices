package com.cargo.tracking.api;

import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventRepository;
import com.cargo.tracking.v1.BatchReportLocationRequest;
import com.cargo.tracking.v1.BatchReportLocationResponse;
import com.cargo.tracking.v1.TrackingEvent;
import com.cargo.tracking.v1.TrackingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.StreamObserver;
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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the client-streaming {@code BatchReportLocation}
 * RPC — a partner uploads a batch of buffered pings on one stream and
 * receives a single accepted/rejected summary.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=tracking-batch-test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BatchReportLocationIT {

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
    private TrackingServiceGrpc.TrackingServiceStub asyncClient;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        readModel.deleteAll();
        channel = InProcessChannelBuilder.forName("tracking-batch-test")
                .usePlaintext()
                .build();
        asyncClient = TrackingServiceGrpc.newStub(channel);
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
    void batch_persists_every_valid_event_and_summarizes() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<BatchReportLocationResponse> result = new AtomicReference<>();

        StreamObserver<BatchReportLocationRequest> req = asyncClient.batchReportLocation(
                new StreamObserver<>() {
                    @Override public void onNext(BatchReportLocationResponse r) { result.set(r); }
                    @Override public void onError(Throwable t) { done.countDown(); }
                    @Override public void onCompleted() { done.countDown(); }
                });

        for (int i = 0; i < 3; i++) {
            req.onNext(BatchReportLocationRequest.newBuilder()
                    .setEvent(TrackingEvent.newBuilder()
                            .setShipmentId(shipmentId.toString())
                            .setLat(52.50 + i).setLng(13.40 + i)
                            .build())
                    .build());
        }
        req.onCompleted();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isNotNull();
        assertThat(result.get().getAccepted()).isEqualTo(3);
        assertThat(result.get().getRejected()).isZero();
        assertThat(result.get().getEventIdsList()).hasSize(3);
        assertThat(events.count()).isEqualTo(3);
    }

    @Test
    void batch_counts_bad_events_as_rejected_without_aborting() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<BatchReportLocationResponse> result = new AtomicReference<>();

        StreamObserver<BatchReportLocationRequest> req = asyncClient.batchReportLocation(
                new StreamObserver<>() {
                    @Override public void onNext(BatchReportLocationResponse r) { result.set(r); }
                    @Override public void onError(Throwable t) { done.countDown(); }
                    @Override public void onCompleted() { done.countDown(); }
                });

        // One good event.
        req.onNext(BatchReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.52).setLng(13.40)
                        .build())
                .build());
        // One with a non-UUID shipment id — rejected, not fatal.
        req.onNext(BatchReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId("not-a-uuid")
                        .setLat(0).setLng(0)
                        .build())
                .build());
        // One empty frame — rejected.
        req.onNext(BatchReportLocationRequest.newBuilder().build());
        req.onCompleted();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get().getAccepted()).isEqualTo(1);
        assertThat(result.get().getRejected()).isEqualTo(2);
        assertThat(events.count()).isEqualTo(1);
    }
}
