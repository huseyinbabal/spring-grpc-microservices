# Cargo Tracking Platform — System Spec

Umbrella spec for the `spring-grpc-microservices` training repo. Turns Issue
#1 into a concrete, buildable target. Per-service PRDs, proto draft, and
acceptance criteria live here until they graduate into `docs/specs/`.

---

## 1. Objective

Build a cargo tracking platform as three Spring Boot gRPC microservices with
a production-shaped topology: contract-first protos, per-service Postgres,
outbox + Debezium CDC to Kafka, Envoy gRPC-Web at the edge, Keycloak OAuth2
for external auth, and mTLS for service-to-service traffic.

**Users:**
- Internal ops — create shipments, watch live tracking dashboards
- Customers — look up their shipments and stream live location updates
- Logistics partners (B2B) — push tracking events via gRPC/gRPC-Web

**Non-goals (training scope):**
- Real notification delivery (email/SMS/push) — log to console only
- Multi-region, HA Postgres, Kafka tuning
- Fine-grained authorization beyond "authenticated user"
- Real IoT hardware — tracking ingest is simulated by a test client

---

## 2. Per-Service PRDs

### 2.1 Shipment Service

**Purpose.** System of record for shipments. Owns lifecycle, status
transitions, and the outbox that publishes domain events.

**Entity.**
```
Shipment {
  id            UUID          (server-assigned)
  tracking_code string        (human-friendly, unique, e.g. CT-2026-000123)
  origin        Address
  destination   Address
  carrier       string
  status        ShipmentStatus  // CREATED → IN_TRANSIT → DELIVERED → CANCELLED
  weight_kg     double
  eta           Timestamp     (nullable)
  created_at    Timestamp
  updated_at    Timestamp
}
Address { line1, city, country, postal_code }
```

**Operations.**
- `CreateShipment` — validates, assigns id + tracking_code, status=CREATED
- `GetShipment` — by id or tracking_code
- `ListShipments` — paginated, filter by status + carrier
- `UpdateShipmentStatus` — enforces legal transitions, writes outbox event
- `CancelShipment` — only from CREATED or IN_TRANSIT

**Outbox events** (JSON on Kafka topic `cargo.shipment.events`):
- `ShipmentCreated`
- `ShipmentStatusChanged`
- `ShipmentCancelled`

**Acceptance criteria.**
- [ ] `CreateShipment` persists row + outbox row in one transaction
- [ ] Illegal status transitions return `FAILED_PRECONDITION`
- [ ] Debezium connector publishes outbox rows to Kafka in order per shipment
- [ ] Integration test spins up Postgres + Kafka + Debezium via Testcontainers and asserts event on topic
- [ ] All RPCs require a valid Keycloak JWT; anonymous calls return `UNAUTHENTICATED`

---

### 2.2 Tracking Service

**Purpose.** Ingests location events for a shipment and exposes live +
historical tracking. Maintains its own read model built from Shipment events
plus tracking events.

**Entities.**
```
TrackingEvent {
  id           UUID
  shipment_id  UUID
  lat          double
  lng          double
  recorded_at  Timestamp
  source       string   // "partner:acme", "sim:test-client", etc.
}

ShipmentReadModel {
  shipment_id    UUID   (PK)
  tracking_code  string
  status         ShipmentStatus
  last_lat       double
  last_lng       double
  last_update_at Timestamp
}
```

**Operations.**
- `ReportLocation` — unary, partner/simulator pushes a single event
- `GetTracking` — current snapshot for a shipment
- `StreamTracking` — **server streaming**, pushes live events for one shipment until client cancels or shipment is DELIVERED/CANCELLED

**Consumers.**
- `cargo.shipment.events` — updates ShipmentReadModel on status changes
- (Slice 6) sync enrichment when read model is missing a shipment

**Acceptance criteria.**
- [ ] `ReportLocation` persists the event and fans out to active `StreamTracking` subscribers within 500ms (local)
- [ ] `StreamTracking` closes cleanly when shipment reaches DELIVERED/CANCELLED
- [ ] Consumer of `cargo.shipment.events` is idempotent (replayable without duplicate state)
- [ ] Integration test: simulator pushes 10 events, streaming client receives all 10 in order
- [ ] All RPCs require a valid JWT

---

### 2.3 Notification Service

**Purpose.** Reacts to shipment + tracking events and emits notifications.
**Training scope: channel is console log only.** Architecture must leave
room to plug in email/webhook later.

**Operations.**
- No public gRPC API in training scope (pure consumer). Exposes only a
  `HealthCheck` RPC for consistency.

**Consumers.**
- `cargo.shipment.events` — logs `NOTIFY` lines for CREATED / DELIVERED / CANCELLED
- `cargo.tracking.events` (Slice 7) — logs `NOTIFY` lines for stalled cargo (heuristic: no update for N minutes)

**Acceptance criteria.**
- [ ] Every `ShipmentCreated` event produces exactly one `NOTIFY shipment.created …` log line
- [ ] Consumer offsets commit only after the log line is written (at-least-once)
- [ ] Integration test asserts the log line using a captured appender

---

## 3. Proto Draft

Layout under `proto/cargo/`:

```
proto/
  cargo/
    common/v1/common.proto
    shipment/v1/shipment.proto
    tracking/v1/tracking.proto
    notification/v1/notification.proto
```

### `cargo/common/v1/common.proto`
```proto
syntax = "proto3";
package cargo.common.v1;
option java_multiple_files = true;
option java_package = "com.cargo.common.v1";

import "google/protobuf/timestamp.proto";

message Address {
  string line1       = 1;
  string city        = 2;
  string country     = 3;
  string postal_code = 4;
}

message Page {
  int32  size  = 1;  // max 100
  string token = 2;  // opaque cursor
}

message PageResult {
  string next_token = 1;
}
```

### `cargo/shipment/v1/shipment.proto`
```proto
syntax = "proto3";
package cargo.shipment.v1;
option java_multiple_files = true;
option java_package = "com.cargo.shipment.v1";

import "google/protobuf/timestamp.proto";
import "cargo/common/v1/common.proto";

enum ShipmentStatus {
  SHIPMENT_STATUS_UNSPECIFIED = 0;
  SHIPMENT_STATUS_CREATED     = 1;
  SHIPMENT_STATUS_IN_TRANSIT  = 2;
  SHIPMENT_STATUS_DELIVERED   = 3;
  SHIPMENT_STATUS_CANCELLED   = 4;
}

message Shipment {
  string                     id            = 1;
  string                     tracking_code = 2;
  cargo.common.v1.Address    origin        = 3;
  cargo.common.v1.Address    destination   = 4;
  string                     carrier       = 5;
  ShipmentStatus             status        = 6;
  double                     weight_kg     = 7;
  google.protobuf.Timestamp  eta           = 8;
  google.protobuf.Timestamp  created_at    = 9;
  google.protobuf.Timestamp  updated_at    = 10;
}

message CreateShipmentRequest {
  cargo.common.v1.Address origin      = 1;
  cargo.common.v1.Address destination = 2;
  string                  carrier     = 3;
  double                  weight_kg   = 4;
}
message CreateShipmentResponse { Shipment shipment = 1; }

message GetShipmentRequest {
  oneof key {
    string id            = 1;
    string tracking_code = 2;
  }
}
message GetShipmentResponse { Shipment shipment = 1; }

message ListShipmentsRequest {
  ShipmentStatus       status_filter  = 1;
  string               carrier_filter = 2;
  cargo.common.v1.Page page           = 3;
}
message ListShipmentsResponse {
  repeated Shipment             shipments = 1;
  cargo.common.v1.PageResult    page      = 2;
}

message UpdateShipmentStatusRequest {
  string         id         = 1;
  ShipmentStatus new_status = 2;
}
message UpdateShipmentStatusResponse { Shipment shipment = 1; }

message CancelShipmentRequest  { string id = 1; }
message CancelShipmentResponse { Shipment shipment = 1; }

service ShipmentService {
  rpc CreateShipment       (CreateShipmentRequest)       returns (CreateShipmentResponse);
  rpc GetShipment          (GetShipmentRequest)          returns (GetShipmentResponse);
  rpc ListShipments        (ListShipmentsRequest)        returns (ListShipmentsResponse);
  rpc UpdateShipmentStatus (UpdateShipmentStatusRequest) returns (UpdateShipmentStatusResponse);
  rpc CancelShipment       (CancelShipmentRequest)       returns (CancelShipmentResponse);
}
```

### `cargo/tracking/v1/tracking.proto`
```proto
syntax = "proto3";
package cargo.tracking.v1;
option java_multiple_files = true;
option java_package = "com.cargo.tracking.v1";

import "google/protobuf/timestamp.proto";
import "cargo/shipment/v1/shipment.proto";

message TrackingEvent {
  string                    id          = 1;
  string                    shipment_id = 2;
  double                    lat         = 3;
  double                    lng         = 4;
  google.protobuf.Timestamp recorded_at = 5;
  string                    source      = 6;
}

message ReportLocationRequest  { TrackingEvent event = 1; }
message ReportLocationResponse { string id = 1; }

message GetTrackingRequest {
  string shipment_id = 1;
}
message GetTrackingResponse {
  string                          shipment_id    = 1;
  cargo.shipment.v1.ShipmentStatus status        = 2;
  double                          last_lat       = 3;
  double                          last_lng       = 4;
  google.protobuf.Timestamp       last_update_at = 5;
}

message StreamTrackingRequest {
  string shipment_id = 1;
}
// Server streams TrackingEvent until shipment reaches a terminal state
// or the client cancels.

service TrackingService {
  rpc ReportLocation (ReportLocationRequest) returns (ReportLocationResponse);
  rpc GetTracking    (GetTrackingRequest)    returns (GetTrackingResponse);
  rpc StreamTracking (StreamTrackingRequest) returns (stream TrackingEvent);
}
```

### `cargo/notification/v1/notification.proto`
```proto
syntax = "proto3";
package cargo.notification.v1;
option java_multiple_files = true;
option java_package = "com.cargo.notification.v1";

message HealthCheckRequest  {}
message HealthCheckResponse { string status = 1; }

service NotificationService {
  rpc HealthCheck (HealthCheckRequest) returns (HealthCheckResponse);
}
```

---

## 4. Data & Events

**Postgres schemas (one DB per service):**
- `shipment_svc`: `shipments`, `outbox`
- `tracking_svc`: `tracking_events`, `shipment_read_model`, `consumer_offsets` (if needed beyond Kafka)
- `notification_svc`: nothing stateful beyond consumer offsets

**Kafka topics** (JSON values via Debezium default):
- `cargo.shipment.events` — sourced from `shipment_svc.outbox` by Debezium
- `cargo.tracking.events` — produced by Tracking Service directly (Slice 4+)

**Outbox row shape:**
```
outbox { id UUID PK, aggregate_type text, aggregate_id text,
         event_type text, payload jsonb, created_at timestamptz }
```

---

## 5. External Acceptance Criteria (Umbrella)

- [ ] `buf lint` + `buf breaking` clean on main
- [ ] `make proto` regenerates Java stubs + grpc-web TypeScript client
- [ ] All three services reconcile under Flux in a local kind cluster and pass a `grpcurl` smoke test via port-forward
- [ ] Keycloak realm boots with `cargo` realm + `cargo-client` confidential client
- [ ] Envoy routes gRPC-Web → Shipment + Tracking, terminates TLS
- [ ] Internal calls (service → service) require client certs (mTLS)
- [ ] End-to-end demo: create shipment → stream tracking → console-log notification — all in one `make demo` target

---

## 6. Commands

```sh
make lint    # buf lint + mvn validate + helm lint
make proto   # buf generate → gen/java + gen/grpc-web
make build   # mvn -T1C package
make test    # mvn verify (unit + Testcontainers integration)
make cluster-up    # kind create + flux bootstrap + reconcile deploy/flux/clusters/local
make cluster-down  # kind delete cluster
make demo    # scripted e2e: create shipment, push tracking, assert notify log
make clean   # wipe gen/ + target/
```

---

## 7. Project Structure

```
proto/                         # buf module, contract-first
common-grpc/                   # shared interceptors, JWT auth, mTLS config
services/
  shipment/                    # Spring Boot + grpc-spring-boot-starter
  tracking/
  notification/
deploy/
  kind/                        # local cluster bootstrap
  flux/                        # Flux CD kustomizations
  helm/{shipment,tracking,notification}/
  debezium/                    # Kafka Connect connector JSON
  envoy/                       # envoy.yaml (gRPC-Web + TLS)
  keycloak/                    # realm export
docs/specs/                    # graduates from SPEC.md
scripts/                       # dev + e2e scripts
SPEC.md                        # this file
```

---

## 8. Code Style

- **Java 21**, records for DTOs, no Lombok
- **Package root**: `com.cargo.<service>`
- **Layering**: `api/` (gRPC services) → `domain/` (entities + services) → `persistence/` (JPA repos) — no cross-layer leaks
- **Errors**: map domain exceptions to gRPC `Status` in a single `GrpcExceptionAdvice`
- **Logging**: SLF4J + JSON encoder, always include `shipment_id` MDC
- **Null**: reject at gRPC boundary, never pass `null` across layers
- **Proto**: follow buf `STANDARD` lint; enums use `<ENUM>_UNSPECIFIED = 0`

---

## 9. Testing Strategy

- **Unit** — JUnit 5 + AssertJ, mock only at ports (repos, Kafka producer)
- **Integration** — Testcontainers: Postgres, Kafka, Debezium, Keycloak (realm import). No mocks for infra.
- **Contract** — `buf breaking` in CI against `origin/main`
- **TDD flow** — RED failing test → GREEN minimal impl → commit per slice
- **E2E** — `make demo` script exits non-zero on missing notify log

Coverage target: 80% line on `domain/` packages. No target on generated code.

---

## 10. Boundaries

**Always do**
- Contract-first: proto change → `buf generate` → code
- One transaction for domain write + outbox insert
- Authenticated RPCs only; reject anonymous at interceptor
- Testcontainers for anything touching Postgres/Kafka/Debezium

**Ask first**
- Adding a new service or new Kafka topic
- Changing proto package layout or service boundaries
- Pulling in a new infra dep (Redis, Elasticsearch, etc.)
- Anything that touches `deploy/flux/` production overlays

**Never**
- Share a database between services
- Call another service's DB directly — always via gRPC
- Publish to Kafka from inside a request path (use outbox)
- Skip `buf lint` or `buf breaking` to land a proto change
- Commit generated code from `gen/` or `target/`
