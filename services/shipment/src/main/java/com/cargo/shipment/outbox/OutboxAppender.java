package com.cargo.shipment.outbox;

import com.cargo.shipment.persistence.OutboxEntity;
import com.cargo.shipment.persistence.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a row into the transactional outbox. Callers invoke
 * {@link #append(String, String, String, Object)} from inside their
 * own {@code @Transactional} method so the outbox insert and the
 * domain write commit (or roll back) atomically.
 *
 * <p>This class is intentionally transaction-agnostic — it does not
 * open a new transaction. If called outside an active transaction the
 * insert auto-commits and the same-tx guarantee is lost.
 *
 * <p>If a span is active when {@code append()} runs, the W3C
 * {@code traceparent} of that span is captured into the outbox row.
 * The Debezium Outbox SMT projects it as a Kafka header so consumers
 * continue the same trace in Tempo instead of starting a new one.
 */
@Component
public class OutboxAppender {

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    private final OutboxRepository repo;
    private final ObjectMapper objectMapper;

    public OutboxAppender(OutboxRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * Serialize {@code payload} to JSON and persist an outbox row.
     *
     * @param aggregateType the Debezium Outbox SMT {@code aggregate_type}
     *     — e.g. {@code "shipment"}
     * @param aggregateId the aggregate's primary key as a string — used
     *     by the SMT as the Kafka message key so events for the same
     *     aggregate land in the same partition in order
     * @param type event type — e.g. {@code "shipment.created"},
     *     {@code "shipment.status.changed"}
     * @param payload any Jackson-serializable object
     */
    public void append(String aggregateType, String aggregateId, String type, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "outbox payload is not JSON-serializable: " + payload, e);
        }
        OutboxEntity entity =
                new OutboxEntity(UUID.randomUUID(), aggregateType, aggregateId, type, json);
        entity.setTracingSpanContext(currentTraceparent());
        repo.save(entity);
    }

    /**
     * Inject the current OTel context into a W3C {@code traceparent}
     * string, or return {@code null} if no valid span is active.
     */
    private static String currentTraceparent() {
        if (!Span.current().getSpanContext().isValid()) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>(2);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, SETTER);
        return carrier.get("traceparent");
    }
}
