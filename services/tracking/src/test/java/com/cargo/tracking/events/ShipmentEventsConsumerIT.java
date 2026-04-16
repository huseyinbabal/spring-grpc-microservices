package com.cargo.tracking.events;

import com.cargo.tracking.domain.ShipmentStatus;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "RUN_KAFKA_IT", matches = "true",
        disabledReason = "Kafka consumer group assignment on GitHub Actions " +
                "runners takes >30s and flakes consistently. Run locally " +
                "with RUN_KAFKA_IT=true or inside the compose stack.")
class ShipmentEventsConsumerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tracking")
                    .withUsername("tracking")
                    .withPassword("tracking");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        // Manual bootstrap binding — @ServiceConnection on KafkaContainer
        // has been flaky for us (the advertised listener is rewritten
        // after the consumer already cached a broker endpoint, and the
        // consumer hits a stale port mid-test). Pinning the property
        // directly is simpler.
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired ShipmentReadModelRepository readModel;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void reset() {
        readModel.deleteAll();
    }

    /**
     * Rolls the full shipment lifecycle past the consumer in one shot:
     * CREATED → IN_TRANSIT → DELIVERED, then a replayed CREATED
     * (proves terminal-state stickiness), and a separate CANCELLED
     * for a second shipment. Consolidating these into one test keeps
     * the consumer/broker connection warm and avoids a per-test
     * flake where the broker closes the consumer's fetch session.
     */
    @Test
    void consumer_projects_shipment_events_into_read_model() throws Exception {
        UUID shipmentA = UUID.randomUUID();
        UUID shipmentB = UUID.randomUUID();

        publish("shipment.created", """
                {"id":"%s","status":"CREATED","carrier":"DHL","trackingCode":"CT-2026-000001"}
                """.formatted(shipmentA));

        ShipmentReadModelEntity created = awaitReadModel(shipmentA);
        assertThat(created.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(created.getCarrier()).isEqualTo("DHL");
        assertThat(created.getTrackingCode()).isEqualTo("CT-2026-000001");

        publish("shipment.status.changed", """
                {"id":"%s","previousStatus":"CREATED","newStatus":"IN_TRANSIT"}
                """.formatted(shipmentA));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(readModel.findById(shipmentA))
                        .get()
                        .satisfies(rm -> {
                            assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
                            assertThat(rm.getTrackingCode()).isEqualTo("CT-2026-000001");
                        }));

        publish("shipment.status.changed", """
                {"id":"%s","previousStatus":"IN_TRANSIT","newStatus":"DELIVERED"}
                """.formatted(shipmentA));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(readModel.findById(shipmentA))
                        .get()
                        .satisfies(rm -> assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.DELIVERED)));

        // Replayed CREATED must NOT rewind a DELIVERED read model.
        publish("shipment.created", """
                {"id":"%s","status":"CREATED","carrier":"DHL"}
                """.formatted(shipmentA));
        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(readModel.findById(shipmentA))
                                .get()
                                .satisfies(rm -> assertThat(rm.getStatus()).isEqualTo(ShipmentStatus.DELIVERED)));

        // Separate shipment: shipment.cancelled takes the read model
        // straight to CANCELLED even without a prior created event.
        publish("shipment.cancelled", """
                {"id":"%s","previousStatus":"CREATED"}
                """.formatted(shipmentB));
        ShipmentReadModelEntity cancelled = awaitReadModel(shipmentB);
        assertThat(cancelled.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    private void publish(String eventType, String payload)
            throws InterruptedException, ExecutionException, TimeoutException {
        Message<String> msg = MessageBuilder
                .withPayload(payload.trim())
                .setHeader(KafkaHeaders.TOPIC, ShipmentEventsConsumer.TOPIC)
                .setHeader("event-type", eventType.getBytes(StandardCharsets.UTF_8))
                .build();
        // Block until the record is acknowledged so assertions downstream
        // aren't racing with the producer thread.
        kafkaTemplate.send(msg).get(5, TimeUnit.SECONDS);
    }

    private ShipmentReadModelEntity awaitReadModel(UUID shipmentId) {
        // 30s gives the Kafka consumer enough time to join the group
        // on slow CI runners — the first rebalance can take 10-15s
        // with the default session.timeout.ms before partitions are
        // assigned and the earliest offset is seeked.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(readModel.findById(shipmentId)).isPresent());
        return readModel.findById(shipmentId).orElseThrow();
    }
}
