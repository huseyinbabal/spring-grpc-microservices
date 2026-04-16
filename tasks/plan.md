# Implementation Plan — Cargo Tracking Platform

Derived from [`SPEC.md`](../SPEC.md). Organizes the work into vertical,
PR-sized tasks. **Deployment target: Docker Compose** — the whole stack
(infra + three services) runs locally via a single `compose.yaml`.
Kubernetes / Flux / Helm are explicitly deferred until after v0.1.0.

Each task is a thin end-to-end slice (proto → domain → persistence → API →
test, or infra → compose → smoke test) and is intended to land as **one PR**.

**Rule of thumb:** a task is done when a PR merges that (a) changes
behavior visible to a test or a compose-level smoke check and (b) ships
that verification green.

---

## Dependency Graph

```
┌──────────────────────────────┐
│ Phase 0 — Foundations        │   CI pipeline + compose scaffold
│  TX.1   CI (buf lint)        │   with the shared Kafka broker
│  TY.1   compose stack        │
└──────────────┬───────────────┘
               │  CF1
               ▼
┌──────────────────────────────┐
│ Phase 1 — common-grpc        │   JWT interceptor + error advice
│  T1.1..T1.3                  │   (+ CI picks up mvn verify)
└──────────────┬───────────────┘
               │  C1
               ▼
┌──────────────────────────────┐
│ Phase 2a — Shipment CRUD     │   5 RPCs, Testcontainers IT
│  T2.1..T2.8                  │
└──────────────┬───────────────┘
               │  C2
               ▼
┌──────────────────────────────┐
│ Phase 2b — Outbox + CDC      │   outbox tx, Debezium, Kafka (TC)
│  T3.1..T3.4                  │
└──────────────┬───────────────┘
               │  C3
               ▼
┌──────────────────────────────┐
│ Phase 2c — Shipment in stack │   Dockerfile, GHCR image, compose
│  TY.S1..TY.S4                │   entry (postgres-shipment,
│                              │   debezium connect, shipment svc)
└──────────────┬───────────────┘
               │  CY1
               ▼
      ┌────────┴────────┐
      │                 │
      ▼                 ▼
┌──────────────┐ ┌──────────────────────┐
│ Phase 3a–3c  │ │ Phase 4 — Notifier   │
│ Tracking     │ │  T7.1..T7.3          │
│ T4..T6       │ │  then TY.N1..TY.N3   │
│ then         │ │       ↓              │
│ TY.T1..TY.T3 │ │       CY3            │
│     ↓        │ └──────────┬───────────┘
│     CY2      │            │
└──────┬───────┘            │
       │                    │
       └──────────┬─────────┘
                  │
                  ▼
      ┌──────────────────────┐
      │ Phase 5 — Edge+auth  │  Keycloak + Envoy gRPC-Web
      │  T8.1..T8.3          │  (compose services)
      └──────────┬───────────┘
                 │  CY4
                 ▼
      ┌──────────────────────┐
      │ Phase 6 — Ship       │  make demo, JSON logs,
      │  T9.1..T9.3          │  Prometheus + Grafana
      └──────────┬───────────┘
                 │  C9 → v0.1.0
                 ▼
               SHIP
```

**Critical path:** CF1 → C1 → C2 → C3 → CY1 → (CY2‖CY3) → CY4 → C9.
**Parallelizable after CY1:** Phase 3 (Tracking) and Phase 4 (Notification)
are independent and can run as two PR streams.

---

## Slicing Philosophy

- **Vertical over horizontal.** Each task touches every layer it needs
  and lands a passing test or a reconciled resource.
- **One PR per task.** Keep diffs reviewable. Rename if a task starts
  sprawling.
- **Testcontainers for logic, compose for topology.** Inner-loop IT
  uses Testcontainers; `compose.yaml` is the "whole stack wired up"
  verification target at checkpoints CY1–CY4. Compose is cheap — run
  it locally whenever you want, no cluster to keep alive.
- **No generated artifacts in git.** `gen/`, `target/`, built images —
  all gitignored. GHCR is the image store for tagged releases; compose
  can also `build:` locally from each service's Dockerfile.
- **Kubernetes deferred.** Helm charts, Flux, cert-manager, Kafka
  operators — none of that until after v0.1.0.

---

## Phase 0 — Foundations (CI + compose scaffold)

Goal: `main` is protected by CI, and `docker compose up -d` brings up
the shared `cargo-net` network plus Kafka so later phases can layer
their services onto the same stack.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TX.1** | `ci: add buf lint + breaking GitHub Actions workflow` | `.github/workflows/ci.yml` runs `buf lint` and `buf breaking --against .git#branch=main` on every PR | PR from a branch with a proto break fails CI |
| **TY.1** | `deploy(compose): stack scaffold with Kafka` | `compose.yaml` at repo root declares the `cargo` network, named volumes, and a single-node Kafka (KRaft) broker with healthcheck | `docker compose -f compose.yaml config --quiet` clean; `docker compose up -d kafka` then `kafka-topics.sh --list` via `docker compose exec` succeeds |

**Checkpoint CF1:** `docker compose up -d` starts the stack and `docker
compose ps` shows Kafka healthy. CI is green on main.

---

## Phase 1 — common-grpc (Slice 1)

Goal: shared library consumable by the three services.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T1.1** | `common-grpc: scaffold module + parent pom` | Parent pom + `common-grpc` child build with `mvn -pl common-grpc -am package`; CI workflow now runs `mvn verify` | CI goes green with mvn step |
| **T1.2** | `common-grpc: JwtAuthInterceptor` | Unsigned JWT → `UNAUTHENTICATED`; valid → principal on gRPC `Context` | JUnit with mock JWKS |
| **T1.3** | `common-grpc: GrpcExceptionAdvice` | `NotFoundException → NOT_FOUND`, `IllegalTransitionException → FAILED_PRECONDITION` | JUnit |

> **T1.4 (MtlsClientConfig helper) is dropped** — mTLS between services
> is deferred past v0.1.0 (see below); the helper would be scaffolding
> for a feature we're not shipping. If/when mTLS comes back, reopen it.

**Checkpoint C1:** `mvn -pl common-grpc verify` green on CI.

---

## Phase 2a — Shipment Service CRUD (Slice 2)

Goal: five RPCs backed by Postgres under Testcontainers IT.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T2.1** | `proto: commit cargo/common/v1 + cargo/shipment/v1` | `buf lint` clean, `make proto` generates Java | CI |
| **T2.2** | `shipment: scaffold Spring Boot module + gRPC starter` | Boots, `grpcurl list` shows `ShipmentService` with unimplemented RPCs | Smoke test |
| **T2.3** | `shipment: Flyway V1 schema + JPA entity + repo` | TC Postgres applies schema; repo CRUD passes | `*RepoIT` |
| **T2.4** | `shipment: RPC CreateShipment` | Valid → row + Shipment returned with id + tracking_code; invalid → `INVALID_ARGUMENT` | `CreateShipmentIT` |
| **T2.5** | `shipment: RPC GetShipment (id + tracking_code oneof)` | Unknown → `NOT_FOUND`; known → returned | IT |
| **T2.6** | `shipment: RPC ListShipments w/ pagination + filters` | `page.size=2` returns ≤2 + next_token; resume works; filter by status and carrier | IT |
| **T2.7** | `shipment: RPC UpdateShipmentStatus + legal transitions` | Illegal jump → `FAILED_PRECONDITION`; legal → updated | IT |
| **T2.8** | `shipment: RPC CancelShipment` | Cancel from DELIVERED → `FAILED_PRECONDITION` | IT |

**Checkpoint C2:** all 5 RPCs green under Testcontainers, JWT required.

---

## Phase 2b — Shipment outbox + Debezium (Slice 3)

Goal: outbox rows written in the same tx as domain writes, Debezium
publishes `cargo.shipment.events` under Testcontainers IT.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T3.1** | `shipment: Flyway V2 outbox table + OutboxAppender` | `outbox` row written in same tx as shipment; rollback drops both | Repo IT |
| **T3.2** | `shipment: wire outbox into Create/UpdateStatus/Cancel` | Exactly one outbox row per domain event | IT row counts |
| **T3.3** | `shipment: Debezium connector config` | `deploy/debezium/shipment-outbox.json` + topic convention documented | JSON schema unit |
| **T3.4** | `shipment: full PG+Kafka+Debezium Testcontainers IT` | `CreateShipment` → event on `cargo.shipment.events` within 5s | `OutboxCdcIT` |

**Checkpoint C3:** events flow under IT.

---

## Phase 2c — Shipment in the compose stack

Goal: Shipment service + its dedicated Postgres + Kafka Connect
(Debezium) land in `compose.yaml`; GH Actions publishes a
`cargo-shipment` image to GHCR on every main commit.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TY.S1** | `shipment: multi-stage Dockerfile (JLink JRE)` | `docker build` succeeds, image < 250 MB | Local build |
| **TY.S2** | `ci: shipment image build + push to GHCR` | Workflow pushes `ghcr.io/<owner>/cargo-shipment:<sha>` on main + `:<tag>` on release tags | Inspect package in GHCR |
| **TY.S3** | `deploy(compose): postgres-shipment + debezium connect` | Adds `postgres-shipment` (wal_level=logical) + `connect` (Debezium image) services to `compose.yaml` with the outbox connector auto-registered via init container or script | `docker compose up -d` + `curl localhost:8083/connectors/shipment-outbox/status` = RUNNING |
| **TY.S4** | `deploy(compose): shipment service` | Adds `shipment` service to `compose.yaml` pulling the GHCR image (or building locally via `build:`), wired to `postgres-shipment` + `kafka` | `docker compose up -d` + `grpcurl -plaintext localhost:9090 …/CreateShipment` works, event lands on `cargo.shipment.events` |

**Checkpoint CY1:** shipment + deps run green in compose. `grpcurl
CreateShipment` + `kafka-console-consumer` on `cargo.shipment.events`
shows the event.

---

## Phase 3a — Tracking ingest + read model (Slice 4)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T4.1** | `proto: commit cargo/tracking/v1` | buf lint clean | CI |
| **T4.2** | `tracking: scaffold Spring Boot module` | `grpcurl list` shows `TrackingService` | Smoke |
| **T4.3** | `tracking: Flyway V1 + entities + repos` | Schema applies | Repo IT |
| **T4.4** | `tracking: RPC ReportLocation` | Unknown shipment → `NOT_FOUND`; known → stored + RM updated | IT |
| **T4.5** | `tracking: RPC GetTracking` | Returns RM snapshot | IT |
| **T4.6** | `tracking: ShipmentEventsConsumer (idempotent RM)` | Replay same offset twice → identical state | IT w/ Testcontainers Kafka |

**Checkpoint C4:** tracking ingest + RM consumer green.

---

## Phase 3b — Tracking streaming (Slice 5)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T5.1** | `tracking: in-process TrackingEventBus` | Subscribers get events published after subscription | Unit |
| **T5.2** | `tracking: RPC StreamTracking (server streaming)` | Client receives 10 events in order; stream closes on DELIVERED/CANCELLED | `StreamTrackingIT` |
| **T5.3** | `tracking: back-pressure drop-oldest buffer` | Slow consumer → producer latency unchanged | Stress test |

**Checkpoint C5:** streaming end-to-end under IT.

---

## Phase 3c — Sync enrichment (Slice 6)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T6.1** | `tracking: ShipmentClient (plaintext intra-compose)` | `ShipmentClient` wraps a plaintext `ManagedChannel` targeting the in-compose `shipment` service and calls `GetShipment` | IT with both services running in compose |
| **T6.2** | `tracking: sync enrichment fallback in GetTracking` | Missing RM → single Shipment call → cached | IT |

**Checkpoint C6:** sync path tested; Tracking feature-complete.

---

## Phase 3d — Tracking in the compose stack

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TY.T1** | `tracking: multi-stage Dockerfile` | Image builds, < 250 MB | Local |
| **TY.T2** | `ci: tracking image build + push to GHCR` | `ghcr.io/<owner>/cargo-tracking:<sha>` on main | GHCR |
| **TY.T3** | `deploy(compose): postgres-tracking + tracking service` | Adds `postgres-tracking` + `tracking` services to `compose.yaml`, wired to shared `kafka` | `docker compose up -d`; streaming a live `StreamTracking` via `grpcurl` returns events |

**Checkpoint CY2:** `tracking` pod consumes `cargo.shipment.events`
from the in-stack Kafka; `StreamTracking` returns events over gRPC.

---

## Phase 4 — Notification Service (Slice 7) *(parallel with Phase 3 after CY1)*

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T7.1** | `notification: scaffold module + HealthCheck RPC` | Boots, healthcheck returns OK | grpcurl |
| **T7.2** | `notification: ShipmentEventsListener → NOTIFY log` | One `NOTIFY shipment.<type> id=<id>` line per event; offset commits after log | IT w/ appender capture |
| **T7.3** | `notification: StalledCargoDetector scheduled job` | Logs `NOTIFY tracking.stalled id=<id>` for stale entries | Unit + IT |

**Checkpoint C7:** notifications observable under IT.

---

## Phase 4b — Notification in the compose stack

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TY.N1** | `notification: Dockerfile` | Image builds | Local |
| **TY.N2** | `ci: notification image build + push to GHCR` | `ghcr.io/<owner>/cargo-notification:<sha>` on main | GHCR |
| **TY.N3** | `deploy(compose): notification service` | Adds `notification` service to `compose.yaml` (stateless, no DB), wired to `kafka` | `docker compose logs notification` shows NOTIFY lines after Shipment RPCs |

**Checkpoint CY3:** `docker compose logs -f notification` shows NOTIFY
lines for events emitted by the Shipment service running in the same stack.

---

## Phase 5 — Edge + security (Slice 8)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T8.1** | `deploy(compose): Keycloak + cargo realm import` | `keycloak` service with a mounted realm export creating `cargo-client` + test user | `curl localhost:8080/realms/cargo/.well-known/openid-configuration` |
| **T8.2** | `deploy(compose): Envoy + gRPC-Web filter` | `envoy` service fronting shipment + tracking with gRPC-Web + TLS termination | Browser grpc-web client hits `GetShipment` through Envoy |
| **T8.3** | `services: enable JWT requirement across all three` | Interceptor from T1.2 enforced in the stack profile; anonymous call → `UNAUTHENTICATED` | Smoke test w/ + w/o token |

**Checkpoint CY4:** edge + auth verified via Envoy → Keycloak → services
running in compose.

---

## Phase 6 — Ship (Slice 9)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T9.1** | `scripts: make demo against the compose stack` | Script obtains a Keycloak token, calls `CreateShipment`, pushes 5 tracking events, streams, asserts NOTIFY log | `make demo` exits 0 |
| **T9.2** | `services: JSON structured logging + shipment_id MDC` | Log lines parseable; MDC present on every request-path line | `docker compose logs` grep |
| **T9.3** | `deploy(compose): Prometheus + Grafana` | Prometheus scrapes `/actuator/prometheus` from all three services; sample Grafana dashboard JSON mounted | Grafana shows shipment QPS |

**Checkpoint C9 (SHIP):** all umbrella AC from SPEC §5 ticked. Tag
`v0.1.0`. GH Actions release workflow builds tagged images for all
three services.

---

## Phase 7 — Logging Stack (Loki + Grafana)

Centralized log aggregation so all container logs are queryable from
a single Grafana UI without `docker compose logs` grep gymnastics.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T10.1** | `deploy(compose): Loki + Promtail + Grafana logging stack` | Adds `loki`, `promtail`, `grafana` services to `compose.yaml`. Promtail scrapes Docker container logs via volume mount. Grafana starts with Loki datasource pre-provisioned. | `docker compose up -d` → `http://localhost:3001` → Explore → `{container_name="cargo-shipment"}` returns logs |
| **T10.2** | `deploy(grafana): provisioned Loki datasource + dashboard` | `deploy/grafana/provisioning/` contains datasource YAML + an optional overview dashboard JSON so Grafana boots ready-to-use without manual config. | Open Grafana → Loki datasource listed → dashboard shows container log volume |

**Checkpoint C10:** `docker compose up -d` → Grafana at `:3001` shows
live logs from all services via Loki. No code changes in any service.

---

## Phase 8 — Tracing Stack (Grafana Tempo + OTLP)

Derived from [`SPEC.md §11`](../SPEC.md). Distributed tracing so a
`CreateShipment` call is visible end-to-end: shipment gRPC span → JDBC
spans (shipments + outbox insert) → Debezium-delivered Kafka message →
tracking's `ShipmentEventsConsumer` span → notification consumer span,
all in a single trace in Grafana Explore → Tempo.

**Architecture:** services export OTLP/gRPC directly to Tempo on
`:4317`. No OTel Collector (training repo — simplest tooling). No Java
agent — Micrometer Tracing + `opentelemetry-exporter-otlp` via Maven.

**Dependency graph:**

```
    T11.1 (tempo + grafana datasource)        ← infra only
        │
        ▼
    T11.2 (parent pom deps + shipment OTLP)   ← proof of concept
        │
        ├─────────────┬────────────────┐
        ▼             ▼                ▼
    T11.3         T11.4               T11.5
    (tracking)   (notification)   (log-to-trace correlation)
        │             │                │
        └─────────────┴────────────────┘
                      ▼
                   C11 — SHIP
```

T11.3 and T11.4 are parallelizable after T11.2 lands. T11.5 only needs
T11.2 to be useful, but is most valuable after all three services
emit traces.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T11.1** | `deploy(compose): Grafana Tempo + OTLP ingest` | Adds `tempo` service to `compose.yaml` (ports 3200 / 4317 / 4318), `deploy/tempo/tempo-config.yaml` with OTLP receivers + local filesystem storage, `tempo-data` named volume, Grafana `tempo.yaml` datasource provisioning. Tempo healthcheck wired so dependent services wait for `service_healthy`. | `docker compose up -d tempo grafana` → `curl -s localhost:3200/ready` returns 200 → Grafana Explore lists "Tempo" as a datasource (empty — no traces yet). |
| **T11.2** | `observability: tracing deps + shipment OTLP export` | Parent `pom.xml` `<dependencyManagement>` pins `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, and `datasource-micrometer-spring-boot` with explicit versions compatible with Spring Boot 3.3.5. `services/shipment/pom.xml` adds them as compile deps. `services/shipment/src/main/resources/application.yml` adds `management.tracing.sampling.probability=1.0` + `management.otlp.tracing.endpoint`. `compose.yaml` shipment service gets `OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo:4317` + `depends_on: tempo: service_healthy`. | `docker compose up -d --build shipment` then `grpcurl … CreateShipment` → Grafana → Explore → Tempo → search `service.name=shipment` returns a trace with ≥ 3 spans: gRPC server, JDBC insert (shipments), JDBC insert (outbox). |
| **T11.3** | `tracking: OTLP export w/ gRPC client + Kafka consumer spans` | `services/tracking/pom.xml` adds the three tracing deps. `application.yml` adds the `management.tracing` + `management.otlp.tracing` blocks and `spring.kafka.listener.observation-enabled: true`. `compose.yaml` tracking service gets the OTLP env var + `depends_on: tempo`. `ShipmentClient` channel is observation-enabled (Micrometer `GrpcObservationClientInterceptor`). | After a shipment is created, the trace in Tempo now extends: Debezium-sourced Kafka message propagates W3C `traceparent`; tracking's `ShipmentEventsConsumer` span appears as a child of the originating trace. Separately, calling `GetTracking` on a missing RM triggers a `ShipmentClient.GetShipment` child span inside the tracking server span. |
| **T11.4** | `notification: OTLP export w/ Kafka consumer spans` | `services/notification/pom.xml` adds tracing deps. `application.yml` adds tracing blocks + `spring.kafka.listener.observation-enabled: true`. `compose.yaml` notification service gets the OTLP env var + `depends_on: tempo`. | After `CreateShipment` in the running stack, the same trace shown in T11.3 now additionally contains a `notification` service span for the `ShipmentEventsListener` that logs the NOTIFY line — linked via Kafka header propagation. |
| **T11.5** | `observability: log-to-trace correlation (trace_id in logs + Loki derivedFields)` | All three services: `logback-spring.xml` (or inline `logging.pattern.console` in `application.yml`) replaces the pattern to include `[%X{traceId:-}/%X{spanId:-}]`. `deploy/grafana/provisioning/datasources/loki.yaml` adds a `derivedFields` rule that parses `traceId=<hex>` and links to the Tempo datasource. | Open Grafana → Explore → Loki → query `{container_name="cargo-shipment"}` → the log line for a `CreateShipment` call shows a clickable `trace_id` link → clicking opens the trace in Tempo. |

**Checkpoint C11:** Run `make demo` (or equivalent `grpcurl CreateShipment`).
Open Grafana → Explore → Tempo → search `service.name=shipment` →
newest trace is a distributed trace spanning shipment + tracking +
notification services. In the same UI, clicking a log line in Loki
jumps to that trace.

---

## Parallelization

- **After CY1**, two PR streams run independently:
  - **Stream A** — Phase 3 (Tracking: T4→T6 then TY.T*)
  - **Stream B** — Phase 4 (Notification: T7→TY.N*)
- They rejoin at **CY4** (Phase 5 needs both services deployed).
- **Track X (CI)** is not a stream — TX.1 is one-shot at Phase 0 and
  auto-extends as modules are added (T1.1 adds mvn verify, TY.S2/T2/N2
  add per-service image builds).
- **After T11.2 lands**, Phase 8 splits into three parallel streams:
  T11.3 (tracking), T11.4 (notification), T11.5 (log correlation).
  They rejoin at C11.

---

## PR Hygiene

Each task in this plan maps to one PR. PR titles match the "PR title"
column. Keep PRs:

- Single-responsibility — no drive-by changes from other tasks
- Under ~400 LOC diff where feasible (generated code excluded)
- With a "Test plan" section that matches the "Verify" column
- Rebased onto `main` before merge (no merge commits)

---

## Risks

- **Kafka + Postgres + Connect on a single machine** — the full stack
  is ~8 containers by Phase 6 and will want 4–6 GB RAM. Mitigation:
  small memory caps per container, single-broker Kafka, one Connect
  worker, don't run Prometheus+Grafana until Phase 6.
- **Debezium on compose** — connector registration races against
  `postgres-shipment` being ready. Mitigation: init script that retries
  connector POST against `/connectors` until the Connect worker is up.
- **Streaming back-pressure (T5.3)** — naive multicast can block.
  Mitigation: `onBackpressureBuffer` with cap + drop-oldest.
- **Keycloak realm import** — brittle across versions. Mitigation: pin
  Keycloak image tag in compose.
- **Spring Boot 3.3.5 + Micrometer + OTel bridge version drift** —
  `opentelemetry-exporter-otlp` is not in Spring Boot's BOM; picking a
  version that doesn't match the OTel SDK transitively imported by
  `micrometer-tracing-bridge-otel` causes `NoSuchMethodError` at
  startup. Mitigation: pin both in parent pom `<dependencyManagement>`
  using the coordinates documented in Spring Boot 3.3's tracing
  reference for the same minor version.
- **gRPC + Kafka context propagation** — W3C `traceparent` must survive
  gRPC metadata and Kafka headers. Both are handled automatically by
  Micrometer Observation API when `spring.kafka.listener.observation-enabled`
  is on and grpc-spring-boot-starter's observation support is active.
  Mitigation: the T11.3 / T11.4 acceptance criteria explicitly verify
  the cross-service link, not just the local spans.
- **Debezium-forwarded Kafka messages missing `traceparent`** — Debezium
  outbox router copies headers from the outbox row; by default it does
  not inject an OTel trace context. Spring Kafka's consumer still opens
  a new root span when there's no upstream context, so the tracking
  consumer trace may not link back to the originating `CreateShipment`
  span. Mitigation documented in T11.3: accept either a linked trace
  (if the outbox row carried `traceparent`) or a linked root span with
  `shipment_id` attribute; if linked traces are required, add a follow-up
  task to write `traceparent` into the outbox row at write time.

---

## Deferred / Optional (not in v0.1.0)

- Kubernetes, Helm charts, Flux, cert-manager, Hetzner k3s — the whole
  k8s deployment story is pushed past v0.1.0
- mTLS between services (keeping plaintext intra-stack for v0.1.0;
  JWT + Envoy TLS at the edge is enough)
- Cosign image signing
- Multi-tenant data isolation
- Real email/SMS/push delivery
- Production Kafka sizing or HA Postgres
- Fine-grained RBAC beyond "authenticated"
- Horizontal scaling of streaming subscribers
