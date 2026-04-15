CREATE TABLE shipments (
    id                     UUID PRIMARY KEY,
    tracking_code          TEXT NOT NULL UNIQUE,

    origin_line1           TEXT NOT NULL,
    origin_city            TEXT NOT NULL,
    origin_country         TEXT NOT NULL,
    origin_postal_code     TEXT NOT NULL,

    destination_line1      TEXT NOT NULL,
    destination_city       TEXT NOT NULL,
    destination_country    TEXT NOT NULL,
    destination_postal_code TEXT NOT NULL,

    carrier                TEXT NOT NULL,
    status                 TEXT NOT NULL,
    weight_kg              DOUBLE PRECISION NOT NULL,
    eta                    TIMESTAMPTZ,

    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_shipments_status ON shipments (status);
CREATE INDEX idx_shipments_carrier ON shipments (carrier);
