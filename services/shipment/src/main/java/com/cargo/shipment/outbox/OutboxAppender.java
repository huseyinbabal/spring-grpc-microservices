package com.cargo.shipment.outbox;

import com.cargo.shipment.persistence.OutboxEntity;
import com.cargo.shipment.persistence.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

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
 */
@Component
public class OutboxAppender {

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
        repo.save(new OutboxEntity(UUID.randomUUID(), aggregateType, aggregateId, type, json));
    }
}
