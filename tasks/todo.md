# Task List — Cargo Tracking Platform

Flat checklist derived from [`plan.md`](./plan.md). **One task = one PR.**
PR titles are the code-block strings next to each task.

Checkpoints (`CF1`, `C1`–`C9`, `CY1`–`CY4`) are human review gates —
stop at each checkpoint and confirm before starting the next phase.

---

## Phase 0 — Foundations (CI + kind + Flux bootstrap)

- [ ] **TX.1** `ci: add buf lint + breaking GitHub Actions workflow`
- [ ] **TY.1** `deploy(kind): bootstrap script + Flux install`
- [ ] **TY.2** `deploy(flux): cluster root kustomization for local`
- [ ] **TY.3** `deploy(flux): cert-manager + ingress-nginx HelmReleases`
- [ ] **CF1 — CHECKPOINT:** `./deploy/kind/bootstrap.sh` → `flux get kustomizations` all Ready

## Phase 1 — common-grpc (Slice 1)

- [ ] **T1.1** `common-grpc: scaffold module + parent pom`  *(CI now runs mvn verify)*
- [ ] **T1.2** `common-grpc: JwtAuthInterceptor`
- [ ] **T1.3** `common-grpc: GrpcExceptionAdvice`
- [ ] **T1.4** `common-grpc: MtlsClientConfig helper`
- [ ] **C1 — CHECKPOINT:** `mvn -pl common-grpc verify` green on CI

## Phase 2a — Shipment CRUD (Slice 2)

- [ ] **T2.1** `proto: commit cargo/common/v1 + cargo/shipment/v1`
- [ ] **T2.2** `shipment: scaffold Spring Boot module + gRPC starter`
- [ ] **T2.3** `shipment: Flyway V1 schema + JPA entity + repo`
- [ ] **T2.4** `shipment: RPC CreateShipment`
- [ ] **T2.5** `shipment: RPC GetShipment (id + tracking_code oneof)`
- [ ] **T2.6** `shipment: RPC ListShipments w/ pagination + filters`
- [ ] **T2.7** `shipment: RPC UpdateShipmentStatus + legal transitions`
- [ ] **T2.8** `shipment: RPC CancelShipment`
- [ ] **C2 — CHECKPOINT:** 5 RPCs green under Testcontainers PG

## Phase 2b — Outbox + Debezium (Slice 3)

- [ ] **T3.1** `shipment: Flyway V2 outbox table + OutboxAppender`
- [ ] **T3.2** `shipment: wire outbox into Create/UpdateStatus/Cancel`
- [ ] **T3.3** `shipment: Debezium connector config`
- [ ] **T3.4** `shipment: full PG+Kafka+Debezium Testcontainers IT`
- [ ] **C3 — CHECKPOINT:** events flow under IT

## Phase 2c — Shipment deployment to kind

- [ ] **TY.S1** `shipment: multi-stage Dockerfile (JLink JRE)`
- [ ] **TY.S2** `ci: shipment image build + push to GHCR`
- [ ] **TY.S3** `deploy(helm): shipment chart`
- [ ] **TY.S4** `deploy(flux): Postgres HelmRelease for shipment`
- [ ] **TY.S5** `deploy(flux): Strimzi Kafka operator + cluster`
- [ ] **TY.S6** `deploy(flux): Kafka Connect + Debezium connector`
- [ ] **TY.S7** `deploy(flux): shipment HelmRelease`
- [ ] **CY1 — CHECKPOINT:** shipment + deps reconciled in kind; grpcurl CreateShipment → event on `cargo.shipment.events`

## Phase 3a — Tracking ingest + RM (Slice 4)

- [ ] **T4.1** `proto: commit cargo/tracking/v1`
- [ ] **T4.2** `tracking: scaffold Spring Boot module`
- [ ] **T4.3** `tracking: Flyway V1 + entities + repos`
- [ ] **T4.4** `tracking: RPC ReportLocation`
- [ ] **T4.5** `tracking: RPC GetTracking`
- [ ] **T4.6** `tracking: ShipmentEventsConsumer (idempotent RM)`
- [ ] **C4 — CHECKPOINT:** ingest + RM consumer green

## Phase 3b — Streaming (Slice 5)

- [ ] **T5.1** `tracking: in-process TrackingEventBus`
- [ ] **T5.2** `tracking: RPC StreamTracking (server streaming)`
- [ ] **T5.3** `tracking: back-pressure drop-oldest buffer`
- [ ] **C5 — CHECKPOINT:** streaming end-to-end under IT

## Phase 3c — Sync enrichment (Slice 6)

- [ ] **T6.1** `tracking: ShipmentClient w/ mTLS from common-grpc`
- [ ] **T6.2** `tracking: sync enrichment fallback in GetTracking`
- [ ] **C6 — CHECKPOINT:** sync path tested; Tracking feature-complete

## Phase 3d — Tracking deployment to kind

- [ ] **TY.T1** `tracking: multi-stage Dockerfile`
- [ ] **TY.T2** `ci: tracking image build + push to GHCR`
- [ ] **TY.T3** `deploy(helm): tracking chart`
- [ ] **TY.T4** `deploy(flux): Postgres HelmRelease for tracking`
- [ ] **TY.T5** `deploy(flux): tracking HelmRelease`
- [ ] **CY2 — CHECKPOINT:** tracking consumes shipment events from in-cluster Kafka

## Phase 4 — Notification (Slice 7) *(parallel with Phase 3 after CY1)*

- [ ] **T7.1** `notification: scaffold module + HealthCheck RPC`
- [ ] **T7.2** `notification: ShipmentEventsListener → NOTIFY log`
- [ ] **T7.3** `notification: StalledCargoDetector scheduled job`
- [ ] **C7 — CHECKPOINT:** notifications observable under IT

## Phase 4b — Notification deployment to kind

- [ ] **TY.N1** `notification: Dockerfile`
- [ ] **TY.N2** `ci: notification image build + push to GHCR`
- [ ] **TY.N3** `deploy(helm): notification chart`
- [ ] **TY.N4** `deploy(flux): notification HelmRelease`
- [ ] **CY3 — CHECKPOINT:** `kubectl logs` on notification pod shows NOTIFY lines from in-cluster shipment events

## Phase 5 — Edge + security (Slice 8)

- [ ] **T8.1** `deploy(flux): Keycloak HelmRelease + cargo realm import`
- [ ] **T8.2** `deploy(flux): Envoy HelmRelease + gRPC-Web filter`
- [ ] **T8.3** `deploy(flux): cert-manager CA issuer + service Certificates`
- [ ] **T8.4** `services: enable JWT requirement across all three`
- [ ] **CY4 — CHECKPOINT:** edge + auth + mTLS verified in kind

## Phase 6 — Ship (Slice 9)

- [ ] **T9.1** `scripts: make demo against kind cluster`
- [ ] **T9.2** `services: JSON structured logging + shipment_id MDC`
- [ ] **T9.3** `deploy(flux): Prometheus + Grafana + ServiceMonitors`
- [ ] **C9 — SHIP:** tag `v0.1.0`, all SPEC §5 criteria ticked
