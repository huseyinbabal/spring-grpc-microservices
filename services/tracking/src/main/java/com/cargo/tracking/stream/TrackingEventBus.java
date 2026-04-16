package com.cargo.tracking.stream;

import com.cargo.tracking.persistence.TrackingEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * In-process pub/sub that fans a newly-recorded tracking event out to
 * every active {@code StreamTracking} subscriber for the same shipment.
 *
 * <p>The bus is deliberately scoped to a single JVM — Phase 3b's
 * streaming contract is <em>in-process only</em>, which is fine for the
 * v0.1.0 tracking topology (one Tracking replica, one partition of
 * events per shipment). A cross-replica fan-out (Kafka → per-replica
 * bus) would be the follow-up when we horizontally scale Tracking.
 *
 * <p>Subscription model: a caller invokes {@link #subscribe(UUID)} and
 * receives a {@link Subscription} holding a per-subscriber queue.
 * {@link TrackingService#reportLocation publish} pushes the new event
 * onto every subscriber's queue for that shipment. Terminal-state
 * signalling ({@link #close}) lets the streaming RPC tear down all
 * subscribers when the shipment reaches DELIVERED or CANCELLED.
 *
 * <p>T5.1 uses an unbounded {@link LinkedBlockingQueue}; T5.3 will
 * swap it for a drop-oldest bounded buffer.
 */
@Component
public class TrackingEventBus {

    private static final Logger log = LoggerFactory.getLogger(TrackingEventBus.class);

    private final ConcurrentMap<UUID, CopyOnWriteArraySet<Subscription>> subscribers =
            new ConcurrentHashMap<>();

    /** Registers a new subscriber for {@code shipmentId}. */
    public Subscription subscribe(UUID shipmentId) {
        Subscription sub = new Subscription(shipmentId);
        subscribers
                .computeIfAbsent(shipmentId, k -> new CopyOnWriteArraySet<>())
                .add(sub);
        log.debug("subscribed to tracking stream for shipment {}", shipmentId);
        return sub;
    }

    /** Removes a previously registered subscriber. */
    public void unsubscribe(Subscription sub) {
        CopyOnWriteArraySet<Subscription> set = subscribers.get(sub.shipmentId);
        if (set != null) {
            set.remove(sub);
            if (set.isEmpty()) {
                subscribers.remove(sub.shipmentId, set);
            }
        }
        log.debug("unsubscribed from tracking stream for shipment {}", sub.shipmentId);
    }

    /**
     * Fans out {@code event} to every subscriber for its shipment. Safe
     * to call with no subscribers — the call is a no-op.
     */
    public void publish(TrackingEventEntity event) {
        CopyOnWriteArraySet<Subscription> set = subscribers.get(event.getShipmentId());
        if (set == null) {
            return;
        }
        for (Subscription sub : set) {
            sub.offer(event);
        }
    }

    /**
     * Signals end-of-stream to every subscriber for {@code shipmentId}.
     * Called when the shipment reaches a terminal state so the
     * streaming RPC can complete the gRPC response naturally.
     */
    public void close(UUID shipmentId) {
        CopyOnWriteArraySet<Subscription> set = subscribers.remove(shipmentId);
        if (set == null) {
            return;
        }
        for (Subscription sub : set) {
            sub.markClosed();
        }
        log.debug("closed tracking stream for shipment {}", shipmentId);
    }

    /** A single subscription handle. Caller polls {@link #take()}. */
    public static final class Subscription {

        private static final TrackingEventEntity POISON = new TrackingEventEntity();

        private final UUID shipmentId;
        private final BlockingQueue<TrackingEventEntity> queue = new LinkedBlockingQueue<>();

        private Subscription(UUID shipmentId) {
            this.shipmentId = shipmentId;
        }

        void offer(TrackingEventEntity event) {
            queue.offer(event);
        }

        void markClosed() {
            queue.offer(POISON);
        }

        /**
         * Blocks until the next event is available, or returns
         * {@code null} if the subscription has been closed (terminal
         * state) — allowing the caller to complete the gRPC stream.
         */
        public TrackingEventEntity take() throws InterruptedException {
            TrackingEventEntity event = queue.take();
            return event == POISON ? null : event;
        }

        public UUID shipmentId() {
            return shipmentId;
        }
    }
}
