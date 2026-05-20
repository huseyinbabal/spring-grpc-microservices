package com.cargo.shipment.outbox;

import com.cargo.shipment.domain.ShipmentStatus;
import com.cargo.shipment.persistence.OutboxEntity;
import com.cargo.shipment.persistence.OutboxRepository;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.persistence.ShipmentRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link OutboxAppender}. Proves the
 * same-transaction guarantee: a shipment write and an outbox append
 * in one transaction either both commit or both roll back.
 */
@SpringBootTest
@Testcontainers
class OutboxAppenderIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OutboxAppender appender;

    @Autowired
    private ShipmentRepository shipmentRepo;

    @Autowired
    private OutboxRepository outboxRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setup() {
        outboxRepo.deleteAll();
        shipmentRepo.deleteAll();
        tx = new TransactionTemplate(txManager);
    }

    @Test
    void append_persists_outbox_row_with_expected_columns() {
        UUID aggregateId = UUID.randomUUID();

        tx.executeWithoutResult(status ->
                appender.append(
                        "shipment",
                        aggregateId.toString(),
                        "shipment.created",
                        Map.of("id", aggregateId.toString(), "status", "CREATED")));

        List<OutboxEntity> rows = outboxRepo.findAll();
        assertThat(rows).hasSize(1);
        OutboxEntity row = rows.get(0);
        assertThat(row.getId()).isNotNull();
        assertThat(row.getAggregateType()).isEqualTo("shipment");
        assertThat(row.getAggregateId()).isEqualTo(aggregateId.toString());
        assertThat(row.getType()).isEqualTo("shipment.created");
        // Payload round-trips through jsonb, which reformats the JSON
        // (whitespace after `:`, possibly reordered keys) — assert on
        // the content, not exact formatting.
        assertThat(row.getPayload())
                .contains(aggregateId.toString())
                .contains("CREATED");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void shipment_write_and_outbox_append_commit_atomically_on_success() {
        UUID committedShipmentId = tx.execute(status -> {
            ShipmentEntity entity = shipmentRepo.save(newShipment("CT-2026-00000001"));
            appender.append(
                    "shipment",
                    entity.getId().toString(),
                    "shipment.created",
                    Map.of("id", entity.getId().toString()));
            return entity.getId();
        });

        assertThat(shipmentRepo.findById(committedShipmentId)).isPresent();
        assertThat(outboxRepo.count()).isEqualTo(1);
    }

    @Test
    void shipment_write_and_outbox_append_roll_back_together_on_failure() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            ShipmentEntity entity = shipmentRepo.save(newShipment("CT-2026-00000002"));
            appender.append(
                    "shipment",
                    entity.getId().toString(),
                    "shipment.created",
                    Map.of("id", entity.getId().toString()));
            throw new RuntimeException("boom — simulate domain-level failure after outbox append");
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        assertThat(shipmentRepo.count())
                .as("shipment row must be rolled back")
                .isZero();
        assertThat(outboxRepo.count())
                .as("outbox row must be rolled back with the shipment row")
                .isZero();
    }

    @Test
    void append_captures_current_w3c_traceparent_into_outbox_row() {
        OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();
        Tracer tracer = otel.getTracer("outbox-appender-it");
        Span span = tracer.spanBuilder("test-request").startSpan();
        String expectedTraceId = span.getSpanContext().getTraceId();
        UUID aggregateId = UUID.randomUUID();

        try (Scope ignored = span.makeCurrent()) {
            tx.executeWithoutResult(status ->
                    appender.append(
                            "shipment",
                            aggregateId.toString(),
                            "shipment.created",
                            Map.of("id", aggregateId.toString())));
        } finally {
            span.end();
        }

        OutboxEntity row = outboxRepo.findAll().get(0);
        assertThat(row.getTracingSpanContext())
                .as("W3C traceparent of the active span must be captured")
                .isNotNull()
                .startsWith("00-" + expectedTraceId + "-");
    }

    @Test
    void append_without_active_span_leaves_traceparent_null() {
        UUID aggregateId = UUID.randomUUID();

        tx.executeWithoutResult(status ->
                appender.append(
                        "shipment",
                        aggregateId.toString(),
                        "shipment.created",
                        Map.of("id", aggregateId.toString())));

        OutboxEntity row = outboxRepo.findAll().get(0);
        assertThat(row.getTracingSpanContext())
                .as("no active span → no traceparent column → consumer starts a fresh trace")
                .isNull();
    }

    @Test
    void non_json_serializable_payload_throws_illegal_argument() {
        Object unserializable = new Object() {
            private final Object self = this; // Jackson refuses cyclic graph
        };

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                appender.append("shipment", "id-1", "shipment.created", unserializable)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(outboxRepo.count()).isZero();
    }

    private static ShipmentEntity newShipment(String trackingCode) {
        ShipmentEntity s = new ShipmentEntity();
        s.setId(UUID.randomUUID());
        s.setTrackingCode(trackingCode);
        s.setOriginLine1("Alexanderplatz 1");
        s.setOriginCity("Berlin");
        s.setOriginCountry("DE");
        s.setOriginPostalCode("10178");
        s.setDestinationLine1("Rue de Rivoli 1");
        s.setDestinationCity("Paris");
        s.setDestinationCountry("FR");
        s.setDestinationPostalCode("75001");
        s.setCarrier("DHL");
        s.setStatus(ShipmentStatus.CREATED);
        s.setWeightKg(5.0);
        return s;
    }
}
