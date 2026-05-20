package com.cargo.tracking.api;

import com.cargo.common.grpc.error.NotFoundException;
import com.cargo.shipment.v1.ShipmentStatus;
import com.cargo.tracking.domain.TrackingService;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.TrackingEventEntity;
import com.cargo.tracking.stream.TrackingEventBus;
import com.cargo.tracking.v1.BatchReportLocationRequest;
import com.cargo.tracking.v1.BatchReportLocationResponse;
import com.cargo.tracking.v1.GetTrackingRequest;
import com.cargo.tracking.v1.GetTrackingResponse;
import com.cargo.tracking.v1.LiveTrackRequest;
import com.cargo.tracking.v1.LiveTrackResponse;
import com.cargo.tracking.v1.ReportLocationRequest;
import com.cargo.tracking.v1.ReportLocationResponse;
import com.cargo.tracking.v1.StreamTrackingRequest;
import com.cargo.tracking.v1.StreamTrackingResponse;
import com.cargo.tracking.v1.TrackingEvent;
import com.cargo.tracking.v1.TrackingServiceGrpc;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gRPC adapter for the Tracking Service. Translates proto messages to
 * domain calls and delegates to {@link TrackingService}.
 *
 * <p>ReportLocation is wired (T4.4); GetTracking lands in T4.5 and
 * StreamTracking in T5.2.
 */
@GrpcService
public class TrackingGrpcService extends TrackingServiceGrpc.TrackingServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(TrackingGrpcService.class);

    private final TrackingService service;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tracking-stream");
        t.setDaemon(true);
        return t;
    });

    public TrackingGrpcService(TrackingService service) {
        this.service = service;
    }

    @Override
    public void reportLocation(
            ReportLocationRequest request,
            StreamObserver<ReportLocationResponse> responseObserver) {
        if (!request.hasEvent()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("event is required")
                    .asRuntimeException());
            return;
        }
        TrackingEvent event = request.getEvent();
        UUID shipmentId = parseUuid(event.getShipmentId(), "shipment_id");
        if (shipmentId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("shipment_id must be a valid UUID")
                    .asRuntimeException());
            return;
        }
        UUID eventId = event.getId().isEmpty() ? null : parseUuid(event.getId(), "id");
        if (!event.getId().isEmpty() && eventId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("id must be a valid UUID when set")
                    .asRuntimeException());
            return;
        }
        Instant recordedAt = event.hasRecordedAt()
                ? toInstant(event.getRecordedAt())
                : Instant.now();

        TrackingEventEntity persisted = service.reportLocation(
                eventId,
                shipmentId,
                event.getLat(),
                event.getLng(),
                recordedAt,
                event.getSource());

        responseObserver.onNext(ReportLocationResponse.newBuilder()
                .setId(persisted.getId().toString())
                .build());
        responseObserver.onCompleted();
    }

    private static UUID parseUuid(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    @Override
    public void getTracking(
            GetTrackingRequest request,
            StreamObserver<GetTrackingResponse> responseObserver) {
        UUID shipmentId = parseUuid(request.getShipmentId(), "shipment_id");
        if (shipmentId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("shipment_id must be a valid UUID")
                    .asRuntimeException());
            return;
        }
        try {
            ShipmentReadModelEntity rm = service.getTracking(shipmentId);
            GetTrackingResponse.Builder response = GetTrackingResponse.newBuilder()
                    .setShipmentId(rm.getShipmentId().toString())
                    .setStatus(toProtoStatus(rm.getStatus()));
            if (rm.getLastLat() != null) {
                response.setLastLat(rm.getLastLat());
            }
            if (rm.getLastLng() != null) {
                response.setLastLng(rm.getLastLng());
            }
            if (rm.getLastUpdateAt() != null) {
                response.setLastUpdateAt(toTimestamp(rm.getLastUpdateAt()));
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (NotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        }
    }

    private static ShipmentStatus toProtoStatus(com.cargo.tracking.domain.ShipmentStatus status) {
        return switch (status) {
            case CREATED -> ShipmentStatus.SHIPMENT_STATUS_CREATED;
            case IN_TRANSIT -> ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT;
            case DELIVERED -> ShipmentStatus.SHIPMENT_STATUS_DELIVERED;
            case CANCELLED -> ShipmentStatus.SHIPMENT_STATUS_CANCELLED;
        };
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    @Override
    public void streamTracking(
            StreamTrackingRequest request,
            StreamObserver<StreamTrackingResponse> responseObserver) {
        UUID shipmentId = parseUuid(request.getShipmentId(), "shipment_id");
        if (shipmentId == null) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("shipment_id must be a valid UUID")
                    .asRuntimeException());
            return;
        }

        ServerCallStreamObserver<StreamTrackingResponse> serverCall =
                (ServerCallStreamObserver<StreamTrackingResponse>) responseObserver;
        TrackingEventBus.Subscription sub = service.bus().subscribe(shipmentId);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // Parked-pump signal: notified when gRPC's HTTP/2 send window
        // reopens (onReadyHandler) or the call is cancelled.
        Object readyLock = new Object();

        serverCall.setOnCancelHandler(() -> {
            cancelled.set(true);
            // Push a poison so the pump unblocks from take(), unsubscribe
            // so future publishes drop, and wake a flow-control wait.
            sub.cancel();
            service.bus().unsubscribe(sub);
            synchronized (readyLock) {
                readyLock.notifyAll();
            }
        });
        // Native gRPC flow control: fires when the transport can accept
        // more frames after isReady() had gone false (slow client drained
        // its HTTP/2 receive window).
        serverCall.setOnReadyHandler(() -> {
            synchronized (readyLock) {
                readyLock.notifyAll();
            }
        });

        streamExecutor.submit(() -> pumpStream(sub, serverCall, cancelled, readyLock));
    }

    /**
     * Drains the bus subscription to the client while honoring HTTP/2
     * flow control. Before pulling each event the pump parks until
     * {@link ServerCallStreamObserver#isReady()} — so a slow client
     * throttles the server (true backpressure) instead of the server
     * buffering frames without bound. The bus's bounded buffer stays as
     * the last-resort overflow guard for a client that stalls forever.
     */
    private void pumpStream(
            TrackingEventBus.Subscription sub,
            ServerCallStreamObserver<StreamTrackingResponse> observer,
            AtomicBoolean cancelled,
            Object readyLock) {
        try {
            while (!cancelled.get() && !observer.isCancelled()) {
                // Backpressure: park until the client's flow-control
                // window reopens before pulling the next event. The
                // bounded wait re-checks cancellation even if a notify
                // is missed.
                synchronized (readyLock) {
                    while (!observer.isReady()
                            && !cancelled.get()
                            && !observer.isCancelled()) {
                        readyLock.wait(1000);
                    }
                }
                if (cancelled.get() || observer.isCancelled()) {
                    break;
                }
                TrackingEventEntity event = sub.take();
                if (event == null) {
                    // Bus closed (terminal state) — complete the RPC.
                    break;
                }
                observer.onNext(StreamTrackingResponse.newBuilder()
                        .setEvent(toProto(event))
                        .build());
            }
            if (!cancelled.get() && !observer.isCancelled()) {
                observer.onCompleted();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("stream pump failed for shipment {}: {}", sub.shipmentId(), e.toString());
            if (!cancelled.get() && !observer.isCancelled()) {
                observer.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            }
        } finally {
            service.bus().unsubscribe(sub);
        }
    }

    /**
     * Client-streaming bulk ingest. A partner device that buffered pings
     * while offline streams them all on one call; the server persists
     * each and, when the client half-closes, replies once with an
     * accepted/rejected summary. Per-event failures are counted, never
     * fatal — one bad ping must not abort the whole batch.
     */
    @Override
    public StreamObserver<BatchReportLocationRequest> batchReportLocation(
            StreamObserver<BatchReportLocationResponse> responseObserver) {
        return new StreamObserver<>() {
            private final List<String> acceptedIds = new ArrayList<>();
            private int rejected = 0;

            @Override
            public void onNext(BatchReportLocationRequest req) {
                if (!req.hasEvent()) {
                    rejected++;
                    return;
                }
                TrackingEvent event = req.getEvent();
                UUID shipmentId = parseUuid(event.getShipmentId(), "shipment_id");
                if (shipmentId == null) {
                    rejected++;
                    return;
                }
                UUID eventId = event.getId().isEmpty() ? null : parseUuid(event.getId(), "id");
                if (!event.getId().isEmpty() && eventId == null) {
                    rejected++;
                    return;
                }
                Instant recordedAt = event.hasRecordedAt()
                        ? toInstant(event.getRecordedAt())
                        : Instant.now();
                try {
                    TrackingEventEntity persisted = service.reportLocation(
                            eventId, shipmentId, event.getLat(), event.getLng(),
                            recordedAt, event.getSource());
                    acceptedIds.add(persisted.getId().toString());
                } catch (RuntimeException e) {
                    log.warn("batch event rejected: {}", e.toString());
                    rejected++;
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("batchReportLocation stream aborted by client: {}", t.toString());
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(BatchReportLocationResponse.newBuilder()
                        .setAccepted(acceptedIds.size())
                        .setRejected(rejected)
                        .addAllEventIds(acceptedIds)
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * Bidirectional streaming. The client sends subscribe/unsubscribe
     * commands for shipment ids over one long-lived stream; the server
     * streams matching tracking events back on the same stream.
     *
     * <p>Each subscribed shipment gets its own pump thread reading from
     * the {@link TrackingEventBus}. Because a {@link StreamObserver} is
     * not thread-safe, every pump serializes its {@code onNext} on a
     * shared {@code writeLock}.
     */
    @Override
    public StreamObserver<LiveTrackRequest> liveTrack(
            StreamObserver<LiveTrackResponse> responseObserver) {

        ServerCallStreamObserver<LiveTrackResponse> serverCall =
                (ServerCallStreamObserver<LiveTrackResponse>) responseObserver;

        // Serializes onNext across the per-shipment pump threads.
        Object writeLock = new Object();
        Map<UUID, TrackingEventBus.Subscription> subs = new ConcurrentHashMap<>();
        AtomicBoolean done = new AtomicBoolean(false);

        Runnable teardown = () -> {
            if (done.compareAndSet(false, true)) {
                subs.values().forEach(s -> {
                    s.cancel();
                    service.bus().unsubscribe(s);
                });
                subs.clear();
            }
        };
        serverCall.setOnCancelHandler(teardown);

        return new StreamObserver<>() {
            @Override
            public void onNext(LiveTrackRequest req) {
                switch (req.getCommandCase()) {
                    case SUBSCRIBE -> {
                        UUID id = parseUuid(req.getSubscribe(), "subscribe");
                        if (id != null) {
                            subs.computeIfAbsent(id, sid -> {
                                TrackingEventBus.Subscription s = service.bus().subscribe(sid);
                                streamExecutor.submit(
                                        () -> pumpLive(s, serverCall, writeLock, done));
                                return s;
                            });
                        }
                    }
                    case UNSUBSCRIBE -> {
                        UUID id = parseUuid(req.getUnsubscribe(), "unsubscribe");
                        if (id != null) {
                            TrackingEventBus.Subscription s = subs.remove(id);
                            if (s != null) {
                                s.cancel();
                                service.bus().unsubscribe(s);
                            }
                        }
                    }
                    case COMMAND_NOT_SET -> {
                        // Empty frame — nothing to do.
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                log.debug("liveTrack stream aborted by client: {}", t.toString());
                teardown.run();
            }

            @Override
            public void onCompleted() {
                teardown.run();
                synchronized (writeLock) {
                    if (!serverCall.isCancelled()) {
                        serverCall.onCompleted();
                    }
                }
            }
        };
    }

    private void pumpLive(
            TrackingEventBus.Subscription sub,
            ServerCallStreamObserver<LiveTrackResponse> observer,
            Object writeLock,
            AtomicBoolean done) {
        try {
            while (!done.get() && !observer.isCancelled()) {
                TrackingEventEntity event = sub.take();
                if (event == null) {
                    // Subscription closed (terminal state or unsubscribe).
                    break;
                }
                synchronized (writeLock) {
                    if (done.get() || observer.isCancelled()) {
                        break;
                    }
                    observer.onNext(LiveTrackResponse.newBuilder()
                            .setEvent(toProto(event))
                            .build());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("liveTrack pump ended for {}: {}", sub.shipmentId(), e.toString());
        }
    }

    private static TrackingEvent toProto(TrackingEventEntity entity) {
        TrackingEvent.Builder b = TrackingEvent.newBuilder()
                .setId(entity.getId().toString())
                .setShipmentId(entity.getShipmentId().toString())
                .setLat(entity.getLat())
                .setLng(entity.getLng())
                .setSource(entity.getSource() == null ? "" : entity.getSource());
        if (entity.getRecordedAt() != null) {
            b.setRecordedAt(toTimestamp(entity.getRecordedAt()));
        }
        return b.build();
    }
}
