-- Phase 3a schema for the Tracking Service.
--
-- Two tables:
--
-- 1. `tracking_events` — append-only log of every location ping
--    reported for a shipment. Primary key is the client-supplied
--    (or server-generated) UUID so ReportLocation is idempotent:
--    a second request with the same id is a no-op.
--
-- 2. `shipment_read_model` — projection maintained from
--    cargo.shipment.events. Lets GetTracking return shipment
--    status + the latest ping without an RPC hop to Shipment.
--    Updated by the ShipmentEventsConsumer in T4.6; last_lat /
--    last_lng / last_update_at are written by ReportLocation.
CREATE TABLE tracking_events (
    id           UUID PRIMARY KEY,
    shipment_id  UUID NOT NULL,
    lat          DOUBLE PRECISION NOT NULL,
    lng          DOUBLE PRECISION NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL,
    source       TEXT NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL
);

-- StreamTracking and GetTracking both pull the most recent events
-- for a single shipment, so index on (shipment_id, recorded_at DESC).
CREATE INDEX idx_tracking_events_shipment_recorded
    ON tracking_events (shipment_id, recorded_at DESC);

CREATE TABLE shipment_read_model (
    shipment_id     UUID PRIMARY KEY,
    tracking_code   TEXT,
    carrier         TEXT,
    status          TEXT NOT NULL,
    last_lat        DOUBLE PRECISION,
    last_lng        DOUBLE PRECISION,
    last_update_at  TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_shipment_read_model_tracking_code
    ON shipment_read_model (tracking_code);
