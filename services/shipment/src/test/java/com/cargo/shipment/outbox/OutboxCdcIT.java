package com.cargo.shipment.outbox;

import com.cargo.common.v1.Address;
import com.cargo.shipment.persistence.ShipmentRepository;
import com.cargo.shipment.v1.CreateShipmentRequest;
import com.cargo.shipment.v1.CreateShipmentResponse;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.testing.testcontainers.DebeziumContainer;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of the Shipment Service's outbox → Debezium
 * → Kafka path. Wires real Postgres + Kafka + Kafka Connect (Debezium)
 * via Testcontainers, registers the connector from the committed
 * {@code deploy/debezium/shipment-outbox.json}, fires a
 * {@code CreateShipment} RPC through the in-process gRPC server, and
 * asserts that the corresponding event lands on
 * {@code cargo.shipment.events} within 10s.
 *
 * <p>This is the Phase 2b checkpoint (C3) — once this IT is green,
 * the outbox pipeline is proven end to end and Phase 3 (Tracking) can
 * start consuming from {@code cargo.shipment.events}.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "grpc.server.port=-1",
        "grpc.server.in-process-name=shipment-test"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxCdcIT {

    private static final String TOPIC = "cargo.shipment.events";
    private static final String CONNECTOR_NAME = "shipment-outbox-connector";

    private static final Network NETWORK = Network.newNetwork();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres-shipment")
                    .withDatabaseName("shipment")
                    .withUsername("shipment")
                    .withPassword("shipment")
                    // wal_level=logical is required for Debezium's pgoutput plugin.
                    .withCommand(
                            "postgres",
                            "-c", "wal_level=logical",
                            "-c", "max_wal_senders=10",
                            "-c", "max_replication_slots=10");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka");

    @Container
    static final DebeziumContainer CONNECT =
            new DebeziumContainer(DockerImageName.parse("quay.io/debezium/connect:2.7.3.Final"))
                    .withNetwork(NETWORK)
                    .withKafka(KAFKA)
                    .dependsOn(KAFKA, POSTGRES);

    @Autowired
    private ShipmentRepository repo;

    private ManagedChannel channel;
    private ShipmentServiceGrpc.ShipmentServiceBlockingStub client;
    private KafkaConsumer<String, String> consumer;

    @BeforeAll
    static void registerConnector() throws Exception {
        String json = Files.readString(
                Path.of("..", "..", "deploy", "debezium", "shipment-outbox.json"));

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> post = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(CONNECT.getTarget() + "/connectors/"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (post.statusCode() >= 400) {
            throw new IllegalStateException(
                    "failed to register connector: HTTP " + post.statusCode() + " — " + post.body());
        }

        waitForConnectorAndTaskRunning();
        preCreateTopic();
    }

    private static void preCreateTopic() throws Exception {
        // Create the target topic up front so `consumer.subscribe` resolves
        // the partition assignment immediately instead of waiting for
        // Debezium to publish the first message (which triggers topic
        // auto-create but takes O(metadata.max.age.ms) to become visible
        // to a consumer that subscribed before the topic existed).
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all()
                    .get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setup() {
        repo.deleteAll();

        channel = InProcessChannelBuilder.forName("shipment-test")
                .usePlaintext()
                .directExecutor()
                .build();
        client = ShipmentServiceGrpc.newBlockingStub(channel);

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-cdc-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Short metadata refresh so the consumer picks up partition
        // reassignments / new topics within a second instead of the
        // default 5 minutes.
        props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "1000");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
        // Ensure the subscription is active before we start producing events.
        consumer.poll(Duration.ofMillis(500));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (consumer != null) {
            consumer.close(Duration.ofSeconds(2));
        }
        if (channel != null) {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        }
    }

    @Test
    void create_shipment_publishes_event_to_cargo_shipment_events_within_10_seconds() {
        CreateShipmentResponse response = client.createShipment(validRequest());
        String shipmentId = response.getShipment().getId();
        String trackingCode = response.getShipment().getTrackingCode();

        ConsumerRecord<String, String> event = pollForEvent(shipmentId);

        if (event == null) {
            // Dump the Debezium Connect container logs on failure so
            // the CI log has enough context to debug connector/task
            // startup problems without re-running.
            System.err.println("=== Debezium Connect logs ===");
            System.err.println(CONNECT.getLogs());
            System.err.println("=== Kafka logs ===");
            System.err.println(KAFKA.getLogs());
        }

        assertThat(event)
                .as("expected a shipment.created event on %s within 10s for shipment %s",
                        TOPIC, shipmentId)
                .isNotNull();

        // The SMT routes aggregate_id → Kafka message key; the shipment's
        // UUID should appear there (Kafka partition key guarantee).
        assertThat(event.key()).contains(shipmentId);

        // The payload is the jsonb column forwarded as the Kafka value.
        assertThat(event.value())
                .contains(shipmentId)
                .contains(trackingCode)
                .contains("DHL")
                .contains("CREATED");

        // The `type` column is emitted as a Kafka header named `event-type`
        // (see `transforms.outbox.table.fields.additional.placement`).
        Header eventType = event.headers().lastHeader("event-type");
        assertThat(eventType)
                .as("event-type header must be present")
                .isNotNull();
        assertThat(new String(eventType.value(), StandardCharsets.UTF_8))
                .isEqualTo("shipment.created");
    }

    private ConsumerRecord<String, String> pollForEvent(String shipmentId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : records) {
                if (record.key() != null && record.key().contains(shipmentId)) {
                    return record;
                }
                if (record.value() != null && record.value().contains(shipmentId)) {
                    return record;
                }
            }
        }
        return null;
    }

    private static void waitForConnectorAndTaskRunning() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        String lastBody = "";
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(CONNECT.getTarget() + "/connectors/"
                                    + CONNECTOR_NAME + "/status"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                lastBody = response.body();
                JsonNode root = mapper.readTree(lastBody);
                String connectorState = root.path("connector").path("state").asText();
                JsonNode tasks = root.path("tasks");
                boolean allTasksRunning = tasks.isArray() && tasks.size() > 0;
                if (allTasksRunning) {
                    for (JsonNode task : tasks) {
                        if (!"RUNNING".equals(task.path("state").asText())) {
                            allTasksRunning = false;
                            break;
                        }
                    }
                }
                if ("RUNNING".equals(connectorState) && allTasksRunning) {
                    return;
                }
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(
                "connector " + CONNECTOR_NAME + " did not reach RUNNING state within 60s; last status: "
                        + lastBody + "\nConnect logs:\n" + CONNECT.getLogs());
    }

    private static CreateShipmentRequest validRequest() {
        return CreateShipmentRequest.newBuilder()
                .setOrigin(Address.newBuilder()
                        .setLine1("Alexanderplatz 1")
                        .setCity("Berlin")
                        .setCountry("DE")
                        .setPostalCode("10178")
                        .build())
                .setDestination(Address.newBuilder()
                        .setLine1("Rue de Rivoli 1")
                        .setCity("Paris")
                        .setCountry("FR")
                        .setPostalCode("75001")
                        .build())
                .setCarrier("DHL")
                .setWeightKg(7.25)
                .build();
    }
}
