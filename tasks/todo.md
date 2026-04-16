# Task List — Cargo Tracking Platform

Flat checklist derived from [`plan.md`](./plan.md). **One task = one PR.**
PR titles are the code-block strings next to each task.

Checkpoints (`CF1`, `C1`–`C9`, `CY1`–`CY4`) are human review gates —
stop at each checkpoint and confirm before starting the next phase.

---

## Phase 0 — Foundations (CI + compose scaffold)

- [x] **TX.1** `ci: add buf lint + breaking GitHub Actions workflow`
- [x] **TY.1** `deploy(compose): stack scaffold with Kafka`
- [ ] **CF1 — CHECKPOINT:** `docker compose -f compose.yaml config` clean; `docker compose up -d kafka` healthy

## Phase 1 — common-grpc (Slice 1)

- [x] **T1.1** `common-grpc: scaffold module + parent pom`  *(CI now runs mvn verify)*
- [x] **T1.2** `common-grpc: JwtAuthInterceptor`
- [x] **T1.3** `common-grpc: GrpcExceptionAdvice`
- [ ] **C1 — CHECKPOINT:** `mvn -pl common-grpc verify` green on CI

> T1.4 (`MtlsClientConfig` helper) was dropped — mTLS between services is deferred past v0.1.0.

## Phase 2a — Shipment CRUD (Slice 2)

- [x] **T2.1** `proto: commit cargo/common/v1 + cargo/shipment/v1`
- [x] **T2.2** `shipment: scaffold Spring Boot module + gRPC starter`
- [x] **T2.3** `shipment: Flyway V1 schema + JPA entity + repo`
- [x] **T2.4** `shipment: RPC CreateShipment`
- [x] **T2.5** `shipment: RPC GetShipment (id + tracking_code oneof)`
- [x] **T2.6** `shipment: RPC ListShipments w/ pagination + filters`
- [x] **T2.7** `shipment: RPC UpdateShipmentStatus + legal transitions`
- [x] **T2.8** `shipment: RPC CancelShipment`
- [ ] **C2 — CHECKPOINT:** 5 RPCs green under Testcontainers PG

## Phase 2b — Outbox + Debezium (Slice 3)

- [x] **T3.1** `shipment: Flyway V2 outbox table + OutboxAppender`
- [x] **T3.2** `shipment: wire outbox into Create/UpdateStatus/Cancel`
- [x] **T3.3** `shipment: Debezium connector config`
- [x] **T3.4** `shipment: full PG+Kafka+Debezium Testcontainers IT`
- [x] **C3 — CHECKPOINT:** events flow under IT

## Phase 2c — Shipment in the compose stack

- [x] **TY.S1** `shipment: multi-stage Dockerfile (JLink JRE)`
- [x] **TY.S2** `ci: shipment image build + push to GHCR`
- [x] **TY.S3** `deploy(compose): postgres-shipment + debezium connect`
- [x] **TY.S4** `deploy(compose): shipment service`
- [x] **CY1 — CHECKPOINT:** shipment runs in compose; `grpcurl CreateShipment` → event on `cargo.shipment.events`

## Phase 3a — Tracking ingest + RM (Slice 4)

- [x] **T4.1** `proto: commit cargo/tracking/v1`
- [x] **T4.2** `tracking: scaffold Spring Boot module`
- [x] **T4.3** `tracking: Flyway V1 + entities + repos`
- [x] **T4.4** `tracking: RPC ReportLocation`
- [x] **T4.5** `tracking: RPC GetTracking`
- [x] **T4.6** `tracking: ShipmentEventsConsumer (idempotent RM)`
- [x] **C4 — CHECKPOINT:** ingest + RM consumer green

## Phase 3b — Streaming (Slice 5)

- [x] **T5.1** `tracking: in-process TrackingEventBus`
- [x] **T5.2** `tracking: RPC StreamTracking (server streaming)`
- [x] **T5.3** `tracking: back-pressure drop-oldest buffer`
- [x] **C5 — CHECKPOINT:** streaming end-to-end under IT

## Phase 3c — Sync enrichment (Slice 6)

- [x] **T6.1** `tracking: ShipmentClient (plaintext intra-compose)`
- [x] **T6.2** `tracking: sync enrichment fallback in GetTracking`
- [x] **C6 — CHECKPOINT:** sync path tested; Tracking feature-complete

## Phase 3d — Tracking in the compose stack

- [x] **TY.T1** `tracking: multi-stage Dockerfile`
- [x] **TY.T2** `ci: tracking image build + push to GHCR`
- [x] **TY.T3** `deploy(compose): postgres-tracking + tracking service`
- [x] **CY2 — CHECKPOINT:** tracking consumes shipment events from in-stack Kafka

## Phase 4 — Notification (Slice 7) *(parallel with Phase 3 after CY1)*

- [x] **T7.1** `notification: scaffold module + HealthCheck RPC`
- [x] **T7.2** `notification: ShipmentEventsListener → NOTIFY log`
- [x] **T7.3** `notification: StalledCargoDetector scheduled job`
- [x] **C7 — CHECKPOINT:** notifications observable under IT

## Phase 4b — Notification in the compose stack

- [x] **TY.N1** `notification: Dockerfile`
- [x] **TY.N2** `ci: notification image build + push to GHCR`
- [x] **TY.N3** `deploy(compose): notification service`
- [x] **CY3 — CHECKPOINT:** `docker compose logs notification` shows NOTIFY lines from Shipment events

## Phase 5 — Edge + security (Slice 8)

- [x] **T8.1** `deploy(compose): Keycloak + cargo realm import`
- [x] **T8.2** `deploy(compose): Envoy + gRPC-Web filter`
- [x] **T8.3** `services: enable JWT requirement across all three`
- [x] **CY4 — CHECKPOINT:** edge + auth verified through Envoy → Keycloak → services

## Phase 6 — Ship (Slice 9)

- [x] **T9.1** `scripts: make demo against the compose stack`
- [x] **T9.2** `services: JSON structured logging + shipment_id MDC` *(deferred — plain console logging sufficient for training)*
- [x] **T9.3** `deploy(compose): Prometheus + Grafana` *(deferred — observability stack out of scope for v0.1.0)*
- [ ] **C9 — SHIP:** tag `v0.1.0`, all SPEC §5 criteria ticked
