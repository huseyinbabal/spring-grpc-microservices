package com.cargo.tracking.stream;

import com.cargo.tracking.persistence.TrackingEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

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
 * <p>Each subscription buffers up to {@value #BUFFER_SIZE} events.
 * If a slow consumer falls behind, the bus drops the OLDEST
 * buffered event to make room for the new one — a new live position
 * is more useful than a stale one, which is the right trade-off for
 * real-time tracking. A dropped-count is bumped on the subscription
 * so observers can emit metrics later.
 */
@Component
public class TrackingEventBus {

    /**
     * Per-subscription bounded buffer size. 128 comfortably holds a
     * few seconds of backlog at the simulated-client rate (~10
     * events/s) without buffering unbounded memory for stuck
     * subscribers.
     */
    public static final int BUFFER_SIZE = 128;

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
        private final BlockingQueue<TrackingEventEntity> queue =
                new ArrayBlockingQueue<>(BUFFER_SIZE);
        private long dropped = 0;

        private Subscription(UUID shipmentId) {
            this.shipmentId = shipmentId;
        }

        /**
         * Drop-oldest enqueue: when the bounded buffer is full, the
         * oldest unread event is discarded so the new one always
         * lands. Keeping the caller non-blocking is critical because
         * the publish path runs inside the ReportLocation RPC thread
         * — we never want a slow streaming client to backpressure a
         * writer.
         */
        synchronized void offer(TrackingEventEntity event) {
            while (!queue.offer(event)) {
                if (queue.poll() != null) {
                    dropped++;
                }
            }
        }

        void markClosed() {
            // Best-effort — if the buffer is full, evict to make
            // room for the poison so blocked takers unblock promptly.
            while (!queue.offer(POISON)) {
                if (queue.poll() == null) {
                    break;
                }
                dropped++;
            }
        }

        /**
         * Signals cancellation directly to a single subscription — used
         * by the streaming RPC on client-cancel to unblock the pump
         * thread. Unlike {@link TrackingEventBus#close}, this does
         * NOT affect sibling subscribers for the same shipment.
         */
        public void cancel() {
            markClosed();
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

        /** How many events this subscription dropped under backpressure. */
        public long droppedCount() {
            return dropped;
        }
    }
}
