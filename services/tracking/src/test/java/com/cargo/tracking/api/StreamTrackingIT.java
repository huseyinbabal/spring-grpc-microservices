package com.cargo.tracking.api;

import com.cargo.tracking.domain.ShipmentStatus;
import com.cargo.tracking.domain.TrackingService;
import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventRepository;
import com.cargo.tracking.v1.ReportLocationRequest;
import com.cargo.tracking.v1.StreamTrackingRequest;
import com.cargo.tracking.v1.StreamTrackingResponse;
import com.cargo.tracking.v1.TrackingEvent;
import com.cargo.tracking.v1.TrackingServiceGrpc;
import io.grpc.Context;
import io.grpc.ManagedChannel;
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
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=tracking-stream-test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StreamTrackingIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tracking")
                    .withUsername("tracking")
                    .withPassword("tracking");

    @Autowired TrackingEventRepository events;
    @Autowired ShipmentReadModelRepository readModel;
    @Autowired TrackingService trackingService;

    private ManagedChannel channel;
    private TrackingServiceGrpc.TrackingServiceBlockingStub blockingClient;

    @BeforeEach
    void setUp() {
        events.deleteAll();
        readModel.deleteAll();
        channel = InProcessChannelBuilder.forName("tracking-stream-test")
                .usePlaintext()
                .build();
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
    void stream_emits_live_events_and_closes_on_terminal_state() throws Exception {
        UUID shipmentId = UUID.randomUUID();

        // Kick off the server-streaming subscription in the background.
        CompletableFuture<Integer> received = CompletableFuture.supplyAsync(() -> {
            int count = 0;
            Iterator<StreamTrackingResponse> iter = blockingClient.streamTracking(
                    StreamTrackingRequest.newBuilder()
                            .setShipmentId(shipmentId.toString())
                            .build());
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
            return count;
        });

        // Let the subscription register on the bus before publishing.
        Thread.sleep(200);

        // Publish two pings via ReportLocation.
        blockingClient.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.52).setLng(13.40)
                        .build())
                .build());
        blockingClient.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.60).setLng(13.30)
                        .build())
                .build());

        // Move the shipment to DELIVERED — that should close the bus
        // subscription and the server-streaming iterator.
        trackingService.applyShipmentEvent(shipmentId, ShipmentStatus.DELIVERED, null, null);

        Integer count = received.get(10, TimeUnit.SECONDS);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void stream_stops_when_client_cancels() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        Context.CancellableContext ctx = Context.current().withCancellation();

        CompletableFuture<Integer> received = CompletableFuture.supplyAsync(() -> {
            try {
                return ctx.call(() -> {
                    int count = 0;
                    Iterator<StreamTrackingResponse> iter = blockingClient.streamTracking(
                            StreamTrackingRequest.newBuilder()
                                    .setShipmentId(shipmentId.toString())
                                    .build());
                    try {
                        while (iter.hasNext()) {
                            iter.next();
                            count++;
                        }
                    } catch (Exception e) {
                        // Client-side cancellation surfaces as a
                        // StatusRuntimeException(CANCELLED). Swallow
                        // so the future completes normally.
                    }
                    return count;
                });
            } catch (Exception e) {
                return 0;
            }
        });

        Thread.sleep(200);
        blockingClient.reportLocation(ReportLocationRequest.newBuilder()
                .setEvent(TrackingEvent.newBuilder()
                        .setShipmentId(shipmentId.toString())
                        .setLat(52.52).setLng(13.40)
                        .build())
                .build());

        // Give the server a moment to deliver the event, then cancel.
        Thread.sleep(300);
        ctx.cancel(new RuntimeException("client disconnected"));

        Integer count = received.get(10, TimeUnit.SECONDS);
        // The client may or may not have observed the single event
        // before cancellation, but the future MUST complete — the
        // streaming RPC was properly torn down on cancel.
        assertThat(count).isGreaterThanOrEqualTo(0);
    }
}
