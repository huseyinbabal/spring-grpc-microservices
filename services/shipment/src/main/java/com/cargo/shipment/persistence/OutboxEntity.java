package com.cargo.shipment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Row in the transactional outbox. Populated inside the same JDBC
 * transaction as the domain write that caused the event, then tailed
 * by Debezium's PostgreSQL connector (Phase 2b) and routed to Kafka
 * by the Outbox Event Router SMT.
 */
@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String type;

    /**
     * JSON payload stored as a {@code jsonb} column. Using
     * {@link JdbcTypeCode} with {@link SqlTypes#JSON} lets us bind a
     * plain {@code String} to a {@code jsonb} column without Hibernate
     * forcing a cast through {@code bytea}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /**
     * W3C {@code traceparent} of the producing request span, or
     * {@code null} when the outbox row is written outside an active
     * span. The Debezium Outbox SMT projects this column as a Kafka
     * header so consumers continue the same trace.
     */
    @Column(name = "tracingspancontext")
    private String tracingSpanContext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OutboxEntity() {}

    public OutboxEntity(UUID id, String aggregateType, String aggregateId, String type, String payload) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }

    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getTracingSpanContext() { return tracingSpanContext; }
    public void setTracingSpanContext(String tracingSpanContext) { this.tracingSpanContext = tracingSpanContext; }

    public Instant getCreatedAt() { return createdAt; }
}
