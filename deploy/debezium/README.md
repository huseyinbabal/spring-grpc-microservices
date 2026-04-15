# Debezium connector configs

Kafka Connect connector JSON files that wire Debezium's PostgreSQL
connector + the Outbox Event Router SMT into the compose stack
(Phase 2c — `TY.S6`).

## Files

- [`shipment-outbox.json`](./shipment-outbox.json) — tails
  `shipment.public.outbox`, routes every row to
  `cargo.shipment.events` via the Outbox Event Router SMT.

## Topic convention

All three Shipment Service events (`shipment.created`,
`shipment.status.changed`, `shipment.cancelled`) land on a **single**
Kafka topic:

```
cargo.shipment.events
```

The specific event type for each record is carried as a Kafka message
**header** named `event-type` (configured via
`transforms.outbox.table.fields.additional.placement`). Consumers read
the header to discriminate between event types without parsing the
payload.

The Kafka message **key** is the `aggregate_id` column (the shipment
UUID), which guarantees that all events for a single shipment land in
the same partition and are delivered in order.

Topic names for other services will follow the same shape:

```
cargo.tracking.events
cargo.notification.events
```

## Payload shape

The Debezium Outbox SMT emits the raw `payload` column (a `jsonb`
value) as the Kafka message **value**. Current Shipment events (see
`com.cargo.shipment.outbox.events`):

- `shipment.created` — `ShipmentCreatedEvent { id, trackingCode, carrier, status, createdAt }`
- `shipment.status.changed` — `ShipmentStatusChangedEvent { id, trackingCode, previousStatus, newStatus, changedAt }`
- `shipment.cancelled` — `ShipmentCancelledEvent { id, trackingCode, previousStatus, cancelledAt }`

## Registration

The connector is POSTed to Kafka Connect's REST API (default port
8083 inside the compose network) by an init container in
`compose.yaml` once Phase 2c lands (`TY.S6`):

```sh
curl -sf -X POST -H "Content-Type: application/json" \
  http://connect:8083/connectors/ \
  --data @/deploy/debezium/shipment-outbox.json
```
