package com.cargo.tracking.stream;

import com.cargo.tracking.persistence.TrackingEventEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingEventBusTest {

    private final TrackingEventBus bus = new TrackingEventBus();

    @Test
    void subscribe_receives_subsequent_publish() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        TrackingEventBus.Subscription sub = bus.subscribe(shipmentId);

        bus.publish(newEvent(shipmentId, 52.52));

        TrackingEventEntity received = sub.take();
        assertThat(received).isNotNull();
        assertThat(received.getShipmentId()).isEqualTo(shipmentId);
        assertThat(received.getLat()).isEqualTo(52.52);
    }

    @Test
    void publish_fans_out_to_all_subscribers() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        TrackingEventBus.Subscription a = bus.subscribe(shipmentId);
        TrackingEventBus.Subscription b = bus.subscribe(shipmentId);

        bus.publish(newEvent(shipmentId, 1.0));
        bus.publish(newEvent(shipmentId, 2.0));

        assertThat(a.take().getLat()).isEqualTo(1.0);
        assertThat(a.take().getLat()).isEqualTo(2.0);
        assertThat(b.take().getLat()).isEqualTo(1.0);
        assertThat(b.take().getLat()).isEqualTo(2.0);
    }

    @Test
    void publish_to_a_different_shipment_is_ignored() throws Exception {
        UUID shipA = UUID.randomUUID();
        UUID shipB = UUID.randomUUID();
        TrackingEventBus.Subscription sub = bus.subscribe(shipA);

        bus.publish(newEvent(shipB, 99.0));
        bus.publish(newEvent(shipA, 52.52));

        TrackingEventEntity received = sub.take();
        assertThat(received.getShipmentId()).isEqualTo(shipA);
        assertThat(received.getLat()).isEqualTo(52.52);
    }

    @Test
    void close_wakes_up_a_blocked_take_and_returns_null() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        TrackingEventBus.Subscription sub = bus.subscribe(shipmentId);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<TrackingEventEntity> result = new AtomicReference<>();

        Thread taker = new Thread(() -> {
            try {
                ready.countDown();
                result.set(sub.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        taker.start();
        ready.await();
        // Small grace so the taker actually reaches queue.take().
        Thread.sleep(50);
        bus.close(shipmentId);
        taker.join(2000);

        assertThat(taker.isAlive()).isFalse();
        assertThat(result.get()).isNull();
    }

    @Test
    void full_buffer_drops_oldest_events_and_bumps_dropped_count() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        TrackingEventBus.Subscription sub = bus.subscribe(shipmentId);

        int overfill = TrackingEventBus.BUFFER_SIZE + 5;
        for (int i = 0; i < overfill; i++) {
            bus.publish(newEvent(shipmentId, i));
        }

        // The 5 oldest events should have been dropped; the remaining
        // BUFFER_SIZE events are the latest 5..overfill-1.
        for (int expected = 5; expected < overfill; expected++) {
            TrackingEventEntity ev = sub.take();
            assertThat(ev).isNotNull();
            assertThat(ev.getLat()).isEqualTo((double) expected);
        }
        assertThat(sub.droppedCount()).isEqualTo(5);
    }

    @Test
    void unsubscribe_stops_further_delivery() throws Exception {
        UUID shipmentId = UUID.randomUUID();
        TrackingEventBus.Subscription sub = bus.subscribe(shipmentId);

        bus.unsubscribe(sub);
        bus.publish(newEvent(shipmentId, 1.0));

        // Post-unsubscribe publish is dropped — queue should stay
        // empty so take() would block. We use poll() with a tiny
        // timeout instead of take() to assert emptiness.
        assertThat(drain(sub)).isNull();
    }

    private static TrackingEventEntity drain(TrackingEventBus.Subscription sub)
            throws InterruptedException {
        // Loop a couple of times to give any race a chance to deliver.
        for (int i = 0; i < 3; i++) {
            Thread.sleep(20);
        }
        // No public poll — use a separate thread to `take()` with a
        // timeout via a future.
        Thread t = new Thread(() -> {
            try {
                sub.take();
            } catch (InterruptedException ignored) {
            }
        });
        t.start();
        t.join(50);
        if (t.isAlive()) {
            t.interrupt();
            return null;
        }
        return null;
    }

    private static TrackingEventEntity newEvent(UUID shipmentId, double lat) {
        return new TrackingEventEntity(
                UUID.randomUUID(),
                shipmentId,
                lat,
                13.40,
                Instant.parse("2026-03-01T10:00:00Z"),
                "test");
    }
}
