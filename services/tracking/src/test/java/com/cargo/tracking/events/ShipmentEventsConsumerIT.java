package com.cargo.tracking.events;

import com.cargo.tracking.domain.ShipmentStatus;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ShipmentEventsConsumerIT {

    private static final String TOPIC = "cargo.shipment.events";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tracking")
                    .withUsername("tracking")
                    .withPassword("tracking");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Autowired ShipmentReadModelRepository readModel;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void reset() {
        readModel.deleteAll();
    }

    @Test
    void shipment_created_event_inserts_read_model_row() {
        UUID shipmentId = UUID.randomUUID();
        publish("shipment.created", """
                {"id":"%s","status":"CREATED","carrier":"DHL","createdAt":"2026-03-01T10:00:00Z","trackingCode":"CT-2026-000001"}
                """.formatted(shipmentId));

        ShipmentReadModelEntity rm = awaitReadModel(shipmentId);
        assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(rm.getCarrier()).isEqualTo("DHL");
        assertThat(rm.getTrackingCode()).isEqualTo("CT-2026-000001");
    }

    @Test
    void status_changed_event_advances_the_read_model() {
        UUID shipmentId = UUID.randomUUID();
        publish("shipment.created", """
                {"id":"%s","status":"CREATED","carrier":"DHL","trackingCode":"CT-2026-000002"}
                """.formatted(shipmentId));
        awaitReadModel(shipmentId);

        publish("shipment.status.changed", """
                {"id":"%s","previousStatus":"CREATED","newStatus":"IN_TRANSIT","changedAt":"2026-03-01T11:00:00Z"}
                """.formatted(shipmentId));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ShipmentReadModelEntity rm = readModel.findById(shipmentId).orElseThrow();
            assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
            // trackingCode set at creation time is preserved.
            assertThat(rm.getTrackingCode()).isEqualTo("CT-2026-000002");
        });
    }

    @Test
    void delivered_status_is_sticky_against_replays() {
        UUID shipmentId = UUID.randomUUID();
        publish("shipment.created", """
                {"id":"%s","status":"CREATED","carrier":"DHL"}
                """.formatted(shipmentId));
        publish("shipment.status.changed", """
                {"id":"%s","newStatus":"DELIVERED"}
                """.formatted(shipmentId));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(readModel.findById(shipmentId))
                        .get()
                        .satisfies(rm -> assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.DELIVERED)));

        // A replayed CREATED event must NOT rewind the read model.
        publish("shipment.created", """
                {"id":"%s","status":"CREATED","carrier":"DHL"}
                """.formatted(shipmentId));

        // Give the consumer a beat to process the replay then assert
        // the read model is still DELIVERED.
        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(readModel.findById(shipmentId))
                                .get()
                                .satisfies(rm -> assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.DELIVERED)));
    }

    @Test
    void cancelled_event_sets_status_to_cancelled() {
        UUID shipmentId = UUID.randomUUID();
        publish("shipment.cancelled", """
                {"id":"%s","previousStatus":"CREATED","cancelledAt":"2026-03-01T12:00:00Z"}
                """.formatted(shipmentId));

        ShipmentReadModelEntity rm = awaitReadModel(shipmentId);
        assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    private void publish(String eventType, String payload) {
        Message<String> msg = MessageBuilder
                .withPayload(payload.trim())
                .setHeader(KafkaHeaders.TOPIC, TOPIC)
                .setHeader("event-type", eventType.getBytes(StandardCharsets.UTF_8))
                .build();
        kafkaTemplate.send(msg);
    }

    private ShipmentReadModelEntity awaitReadModel(UUID shipmentId) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(readModel.findById(shipmentId)).isPresent());
        return readModel.findById(shipmentId).orElseThrow();
    }
}
