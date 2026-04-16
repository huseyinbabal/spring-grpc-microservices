package com.cargo.tracking.api;

import com.cargo.common.grpc.error.NotFoundException;
import com.cargo.shipment.v1.ShipmentStatus;
import com.cargo.tracking.domain.TrackingService;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.TrackingEventEntity;
import com.cargo.tracking.stream.TrackingEventBus;
import com.cargo.tracking.v1.GetTrackingRequest;
import com.cargo.tracking.v1.GetTrackingResponse;
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
import java.util.UUID;
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

        serverCall.setOnCancelHandler(() -> {
            cancelled.set(true);
            // Push a poison so the pump thread unblocks from take(),
            // then unsubscribe so future publishes are dropped.
            sub.cancel();
            service.bus().unsubscribe(sub);
        });

        streamExecutor.submit(() -> pumpStream(sub, serverCall, cancelled));
    }

    private void pumpStream(
            TrackingEventBus.Subscription sub,
            ServerCallStreamObserver<StreamTrackingResponse> observer,
            AtomicBoolean cancelled) {
        try {
            while (!cancelled.get() && !observer.isCancelled()) {
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
