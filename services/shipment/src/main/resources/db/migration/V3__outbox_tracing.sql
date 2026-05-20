-- Trace propagation across the outbox → Debezium → Kafka boundary.
-- Holds the W3C `traceparent` of the request span that produced the
-- event. The Debezium Outbox Event Router SMT projects this column as
-- a Kafka header named `traceparent`, so consumers continue the same
-- trace in Tempo instead of starting a new one.
--
-- Nullable: events produced outside an active span (e.g. scheduled
-- jobs, replay tooling) still write rows; the consumer simply starts
-- a fresh trace for those.
ALTER TABLE outbox ADD COLUMN tracingspancontext TEXT;
