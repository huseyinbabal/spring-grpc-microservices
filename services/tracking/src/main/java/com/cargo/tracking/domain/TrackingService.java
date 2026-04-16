package com.cargo.tracking.domain;

import com.cargo.common.grpc.error.NotFoundException;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventEntity;
import com.cargo.tracking.persistence.TrackingEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TrackingService {

    private final TrackingEventRepository events;
    private final ShipmentReadModelRepository readModel;

    public TrackingService(
            TrackingEventRepository events,
            ShipmentReadModelRepository readModel) {
        this.events = events;
        this.readModel = readModel;
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

        return persisted;
    }

    /**
     * Returns the current tracking snapshot for {@code shipmentId}.
     * Throws {@link NotFoundException} (→ gRPC NOT_FOUND via
     * GrpcExceptionAdvice) if neither a shipment-events projection nor
     * any tracking ping has been recorded for this shipment yet.
     */
    @Transactional(readOnly = true)
    public ShipmentReadModelEntity getTracking(UUID shipmentId) {
        return readModel.findById(shipmentId).orElseThrow(() ->
                new NotFoundException("no tracking data for shipment " + shipmentId));
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
        ShipmentReadModelEntity rm = readModel.findById(shipmentId).orElseGet(() ->
                new ShipmentReadModelEntity(shipmentId, trackingCode, carrier, newStatus));

        // Sticky terminals: once DELIVERED or CANCELLED, ignore further
        // status updates so a replayed CREATED/IN_TRANSIT event can't
        // rewind the read model.
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
    }
}
