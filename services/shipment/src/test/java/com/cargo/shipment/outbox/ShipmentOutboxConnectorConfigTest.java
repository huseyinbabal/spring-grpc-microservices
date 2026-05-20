package com.cargo.shipment.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the Debezium connector config at
 * {@code deploy/debezium/shipment-outbox.json}. Pure JSON parsing —
 * no database, no Kafka, no Debezium — just asserts that the file is
 * valid JSON with the fields the Outbox Event Router SMT and the
 * Postgres connector need.
 *
 * <p>Runs as a unit test (surefire, not failsafe) so it's fast and
 * doesn't require Docker.
 */
class ShipmentOutboxConnectorConfigTest {

    private static final Path CONFIG_PATH =
            Path.of("..", "..", "deploy", "debezium", "shipment-outbox.json");

    private static JsonNode root;
    private static JsonNode config;

    @BeforeAll
    static void loadConfig() throws IOException {
        assertThat(Files.exists(CONFIG_PATH))
                .as("connector config must exist at %s", CONFIG_PATH.toAbsolutePath())
                .isTrue();
        root = new ObjectMapper().readTree(CONFIG_PATH.toFile());
        config = root.path("config");
    }

    @Test
    void file_is_valid_json_with_top_level_shape() {
        assertThat(root.path("name").asText()).isEqualTo("shipment-outbox-connector");
        assertThat(config.isObject())
                .as("`config` must be an object")
                .isTrue();
    }

    @Test
    void uses_the_postgres_connector_with_pgoutput_plugin() {
        assertThat(config.path("connector.class").asText())
                .isEqualTo("io.debezium.connector.postgresql.PostgresConnector");
        assertThat(config.path("plugin.name").asText()).isEqualTo("pgoutput");
    }

    @Test
    void targets_the_shipment_database_and_outbox_table_only() {
        assertThat(config.path("database.dbname").asText()).isEqualTo("shipment");
        assertThat(config.path("database.hostname").asText()).isEqualTo("postgres-shipment");
        assertThat(config.path("schema.include.list").asText()).isEqualTo("public");
        assertThat(config.path("table.include.list").asText()).isEqualTo("public.outbox");
    }

    @Test
    void uses_dedicated_replication_slot_and_publication() {
        assertThat(config.path("slot.name").asText()).isEqualTo("dbz_shipment_outbox");
        assertThat(config.path("publication.name").asText()).isEqualTo("dbz_shipment_outbox");
    }

    @Test
    void wires_the_outbox_event_router_smt() {
        assertThat(config.path("transforms").asText()).isEqualTo("outbox");
        assertThat(config.path("transforms.outbox.type").asText())
                .isEqualTo("io.debezium.transforms.outbox.EventRouter");
    }

    @Test
    void maps_outbox_columns_to_smt_fields() {
        assertThat(config.path("transforms.outbox.table.field.event.id").asText()).isEqualTo("id");
        assertThat(config.path("transforms.outbox.table.field.event.key").asText())
                .isEqualTo("aggregate_id");
        assertThat(config.path("transforms.outbox.table.field.event.type").asText()).isEqualTo("type");
        assertThat(config.path("transforms.outbox.table.field.event.payload").asText())
                .isEqualTo("payload");
        // Deliberately no `event.timestamp` mapping: `created_at` is a
        // TIMESTAMPTZ which Debezium surfaces as a ZonedTimestamp STRING,
        // and the EventRouter SMT's event.timestamp expects INT64 epoch
        // millis — leaving it unset lets Kafka Connect use the default
        // record timestamp (the connector's source event time).
        assertThat(config.has("transforms.outbox.table.field.event.timestamp")).isFalse();
    }

    @Test
    void routes_all_shipment_events_to_a_single_cargo_topic() {
        assertThat(config.path("transforms.outbox.route.by.field").asText())
                .isEqualTo("aggregate_type");
        assertThat(config.path("transforms.outbox.route.topic.replacement").asText())
                .isEqualTo("cargo.${routedByValue}.events");
    }

    @Test
    void emits_event_type_and_traceparent_as_kafka_message_headers() {
        // `type` → `event-type` lets consumers branch on event kind without
        // parsing the payload. `tracingspancontext` → `traceparent` carries
        // the W3C trace context captured by OutboxAppender so consumer spans
        // join the producing request's trace in Tempo.
        assertThat(config.path("transforms.outbox.table.fields.additional.placement").asText())
                .isEqualTo("type:header:event-type,tracingspancontext:header:traceparent");
    }
}
