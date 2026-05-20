package com.cargo.tracking.api;

import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventRepository;
import com.cargo.tracking.v1.LiveTrackRequest;
import com.cargo.tracking.v1.LiveTrackResponse;
import com.cargo.tracking.v1.ReportLocationRequest;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the bidirectional {@code LiveTrack} RPC — a
 * dashboard opens one stream and dynamically subscribes to shipments;
 * the server streams matching tracking events back on the same stream.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=tracking-live-test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LiveTrackIT {

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
    private TrackingServiceGrpc.TrackingServiceBlockingStub blockingClient;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        readModel.deleteAll();
        channel = InProcessChannelBuilder.forName("tracking-live-test")
                .usePlaintext()
                .build();
        asyncClient = TrackingServiceGrpc.newStub(channel);
        blockingClient = TrackingServiceGrpc.newBlockingStub(channel);
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
    void live_track_streams_events_for_a_subscribed_shipment() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        BlockingQueue<LiveTrackResponse> received = new ArrayBlockingQueue<>(16);

        StreamObserver<LiveTrackRequest> req = asyncClient.liveTrack(new StreamObserver<>() {
            @Override public void onNext(LiveTrackResponse r) { received.offer(r); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });

        req.onNext(LiveTrackRequest.newBuilder().setSubscribe(shipmentId.toString()).build());
        // Let the subscription register on the bus before publishing.
        Thread.sleep(300);

        blockingClient.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.52).setLng(13.40)
                        .build())
                .build());

        LiveTrackResponse event = received.poll(10, TimeUnit.SECONDS);
        assertThat(event).isNotNull();
        assertThat(event.getEvent().getShipmentId()).isEqualTo(shipmentId.toString());

        req.onCompleted();
    }

    @Test
    void live_track_multiplexes_two_shipments_over_one_stream() throws Exception {
        UUID shipmentA = UUID.randomUUID();
        UUID shipmentB = UUID.randomUUID();
        BlockingQueue<LiveTrackResponse> received = new ArrayBlockingQueue<>(16);

        StreamObserver<LiveTrackRequest> req = asyncClient.liveTrack(new StreamObserver<>() {
            @Override public void onNext(LiveTrackResponse r) { received.offer(r); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });

        req.onNext(LiveTrackRequest.newBuilder().setSubscribe(shipmentA.toString()).build());
        req.onNext(LiveTrackRequest.newBuilder().setSubscribe(shipmentB.toString()).build());
        Thread.sleep(300);

        blockingClient.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentA.toString())
                        .setLat(1).setLng(1).build())
                .build());
        blockingClient.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentB.toString())
                        .setLat(2).setLng(2).build())
                .build());

        LiveTrackResponse first = received.poll(10, TimeUnit.SECONDS);
        LiveTrackResponse second = received.poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(java.util.Set.of(
                        first.getEvent().getShipmentId(),
                        second.getEvent().getShipmentId()))
                .containsExactlyInAnyOrder(shipmentA.toString(), shipmentB.toString());

        req.onCompleted();
    }
}
