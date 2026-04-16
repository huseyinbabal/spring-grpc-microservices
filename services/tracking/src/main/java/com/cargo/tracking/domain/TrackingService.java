package com.cargo.tracking.domain;

import com.cargo.common.grpc.error.NotFoundException;
import com.cargo.shipment.v1.GetShipmentResponse;
import com.cargo.shipment.v1.Shipment;
import com.cargo.tracking.client.ShipmentClient;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventEntity;
import com.cargo.tracking.persistence.TrackingEventRepository;
import com.cargo.tracking.stream.TrackingEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);

    private final TrackingEventRepository events;
    private final ShipmentReadModelRepository readModel;
    private final TrackingEventBus bus;
    private final ShipmentClient shipmentClient;

    public TrackingService(
            TrackingEventRepository events,
            ShipmentReadModelRepository readModel,
            TrackingEventBus bus,
            ShipmentClient shipmentClient) {
        this.events = events;
        this.readModel = readModel;
        this.bus = bus;
        this.shipmentClient = shipmentClient;
    }

    /**
     * Records a tracking ping. Idempotent on {@code eventId}: a repeat
     * call with the same id returns the existing row without writing.
     * Updates the shipment read model's last-known position so
     * {@code GetTracking} can answer without a join.
     *
     * <p>If no read-model row exists yet (e.g. the shipment event has
     * not arrived over Kafka yet — the consumer lands in T4.6), a stub
     * row is inserted with {@code status=CREATED}; the consumer will
     * overwrite it when the real event arrives. This keeps
     * {@code ReportLocation} from failing on race conditions.
     */
    @Transactional
    public TrackingEventEntity reportLocation(
            UUID eventId,
            UUID shipmentId,
            double lat,
            double lng,
            Instant recordedAt,
            String source) {
        UUID id = eventId != null ? eventId : UUID.randomUUID();

        TrackingEventEntity persisted = events.findById(id).orElseGet(() ->
                events.save(new TrackingEventEntity(id, shipmentId, lat, lng, recordedAt, source)));

        ShipmentReadModelEntity rm = readModel.findById(shipmentId).orElseGet(() ->
                new ShipmentReadModelEntity(shipmentId, null, null, ShipmentStatus.CREATED));
        // Only advance last-known position if this event is newer than
        // what's already on the read model — out-of-order pings must
        // not rewind the projection.
        if (rm.getLastUpdateAt() == null || recordedAt.isAfter(rm.getLastUpdateAt())) {
            rm.setLastLat(lat);
            rm.setLastLng(lng);
            rm.setLastUpdateAt(recordedAt);
        }
        readModel.save(rm);

        // Fan the new event out to any live StreamTracking
        // subscribers for this shipment. Bus is JVM-local, publish
        // is non-blocking.
        bus.publish(persisted);

        return persisted;
    }

    /**
     * Returns the current tracking snapshot for {@code shipmentId}.
     *
     * <p>Cold-start fallback: if the read model has no row yet
     * (e.g. GetTracking is called before the shipment event has been
     * consumed from {@code cargo.shipment.events}), the service
     * synchronously asks Shipment via {@link ShipmentClient}. Success
     * populates an in-memory projection — nothing is written to the
     * DB because the event consumer will do that asynchronously. If
     * Shipment also doesn't know the shipment, we throw
     * {@link NotFoundException} so the caller gets NOT_FOUND instead
     * of hanging.
     */
    @Transactional(readOnly = true)
    public ShipmentReadModelEntity getTracking(UUID shipmentId) {
        Optional<ShipmentReadModelEntity> cached = readModel.findById(shipmentId);
        if (cached.isPresent()) {
            return cached.get();
        }

        log.debug("read model miss for {} — falling back to Shipment RPC", shipmentId);
        Optional<GetShipmentResponse> upstream =
                shipmentClient.findShipmentById(shipmentId.toString());
        if (upstream.isEmpty() || !upstream.get().hasShipment()) {
            throw new NotFoundException("no tracking data for shipment " + shipmentId);
        }

        // Build a transient projection from the upstream shipment. The
        // Kafka consumer will land the real row shortly and future
        // lookups will hit the cache.
        Shipment shipment = upstream.get().getShipment();
        ShipmentReadModelEntity transientRm = new ShipmentReadModelEntity(
                shipmentId,
                shipment.getTrackingCode(),
                shipment.getCarrier(),
                mapProtoStatus(shipment.getStatus()));
        return transientRm;
    }

    private static ShipmentStatus mapProtoStatus(com.cargo.shipment.v1.ShipmentStatus proto) {
        return switch (proto) {
            case SHIPMENT_STATUS_CREATED -> ShipmentStatus.CREATED;
            case SHIPMENT_STATUS_IN_TRANSIT -> ShipmentStatus.IN_TRANSIT;
            case SHIPMENT_STATUS_DELIVERED -> ShipmentStatus.DELIVERED;
            case SHIPMENT_STATUS_CANCELLED -> ShipmentStatus.CANCELLED;
            default -> ShipmentStatus.CREATED;
        };
    }

    /**
     * Exposes the internal event bus so the gRPC streaming adapter
     * (T5.2 StreamTracking) can subscribe to live event fan-out
     * without the adapter needing its own dependency on the bus.
     */
    public TrackingEventBus bus() {
        return bus;
    }

    /**
     * Applies a shipment-side lifecycle event to the read model. Called
     * by the ShipmentEventsConsumer for each message on
     * {@code cargo.shipment.events}.
     *
     * <p>Idempotent on two axes:
     * <ol>
     *   <li>Replays are safe — applying the same event twice is a no-op
     *       because the status transition is monotonic on the shipment
     *       lifecycle.</li>
     *   <li>Terminal states are sticky — once the read model reaches
     *       {@code DELIVERED} or {@code CANCELLED}, later events cannot
     *       rewind it.</li>
     * </ol>
     */
    @Transactional
    public void applyShipmentEvent(
            UUID shipmentId,
            ShipmentStatus newStatus,
            String trackingCode,
            String carrier) {
        ShipmentReadModelEntity rm = readModel.findById(shipmentId).orElse(null);
        if (rm == null) {
            // First event we've ever seen for this shipment — insert
            // fresh. It's fine for the very first event to land
            // directly in a terminal state (e.g. a shipment.cancelled
            // event for a shipment whose CREATED projection hasn't
            // arrived yet).
            ShipmentReadModelEntity fresh = new ShipmentReadModelEntity(
                    shipmentId, trackingCode, carrier, newStatus);
            readModel.save(fresh);
            return;
        }

        // Sticky terminals: once an existing row reaches DELIVERED or
        // CANCELLED, ignore further status updates so a replayed
        // CREATED/IN_TRANSIT event can't rewind the projection.
        if (rm.getStatus() == ShipmentStatus.DELIVERED
                || rm.getStatus() == ShipmentStatus.CANCELLED) {
            return;
        }

        rm.setStatus(newStatus);
        if (trackingCode != null && !trackingCode.isBlank()) {
            rm.setTrackingCode(trackingCode);
        }
        if (carrier != null && !carrier.isBlank()) {
            rm.setCarrier(carrier);
        }
        readModel.save(rm);

        // Tear down any live StreamTracking subscribers the moment
        // the shipment reaches a terminal state.
        if (newStatus == ShipmentStatus.DELIVERED
                || newStatus == ShipmentStatus.CANCELLED) {
            bus.close(shipmentId);
        }
    }
}
