-- Transactional outbox consumed by Debezium's PostgreSQL connector +
-- Outbox Event Router SMT in Phase 2b. Column names match the
-- Debezium defaults (`aggregate_type`, `aggregate_id`, `type`) per the
-- connector config documented in Issue #1.
CREATE TABLE outbox (
    id             UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id   TEXT NOT NULL,
    type           TEXT NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_outbox_created_at ON outbox (created_at);
CREATE INDEX idx_outbox_aggregate ON outbox (aggregate_type, aggregate_id);
