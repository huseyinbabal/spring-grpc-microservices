# Implementation Plan — Cargo Tracking Platform

Derived from [`SPEC.md`](../SPEC.md). Organizes the work into vertical,
PR-sized tasks. **Deployment target: kind + Flux only** — no docker-compose.

Each task is a thin end-to-end slice (proto → domain → persistence → API →
test, or infra → chart → release) and is intended to land as **one PR**.

**Rule of thumb:** a task is done when a PR merges that (a) changes
behavior visible to a test or reconciled resource and (b) ships that
verification green.

---

## Dependency Graph

```
┌──────────────────────────────┐
│ Phase 0 — Foundations        │   CI pipeline, kind+Flux bootstrap,
│  TX.1   CI (buf lint)        │   shared infra HelmReleases
│  TY.1   kind bootstrap       │
│  TY.2   Flux cluster root    │
│  TY.3   cert-mgr + ingress   │
└──────────────┬───────────────┘
               │  CF1
               ▼
┌──────────────────────────────┐
│ Phase 1 — common-grpc        │   JWT, error advice, mTLS helper
│  T1.1..T1.4                  │   (+ CI picks up mvn verify)
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
│ Phase 2c — Shipment deploy   │   Dockerfile, GHCR workflow,
│  TY.S1..TY.S7                │   Helm chart, Strimzi, Debezium,
│                              │   Postgres, Flux HelmRelease
└──────────────┬───────────────┘
               │  CY1
               ▼
      ┌────────┴────────┐
      │                 │
      ▼                 ▼
┌──────────────┐ ┌──────────────────────┐
│ Phase 3a–3c  │ │ Phase 4 — Notifier   │
│ Tracking     │ │  T7.1..T7.3          │
│ T4..T6       │ │  then TY.N1..TY.N4   │
│ then         │ │       ↓              │
│ TY.T1..TY.T5 │ │       CY3            │
│     ↓        │ └──────────┬───────────┘
│     CY2      │            │
└──────┬───────┘            │
       │                    │
       └──────────┬─────────┘
                  │
                  ▼
      ┌──────────────────────┐
      │ Phase 5 — Edge+auth  │  Keycloak, Envoy gRPC-Web,
      │  T8.1..T8.4          │  cert-manager mTLS
      └──────────┬───────────┘
                 │  CY4
                 ▼
      ┌──────────────────────┐
      │ Phase 6 — Ship       │  make demo (against kind),
      │  T9.1..T9.3          │  JSON logs, Prometheus
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
- **Testcontainers for logic, kind for topology.** Inner-loop IT uses
  Testcontainers; kind+Flux is only for "does this actually deploy"
  verification at checkpoints CY1–CY4.
- **No generated artifacts in git.** `gen/`, `target/`, built images —
  all gitignored. GHCR is the image store.
- **Image tags by commit SHA.** Flux `HelmRelease` values pin to
  `:<sha>` bumped by PR. (Optional: add `ImageUpdateAutomation` later.)

---

## Phase 0 — Foundations (CI + kind scaffolding)

Goal: `main` is protected by CI, a single `make cluster-up` produces a
reconciling kind cluster with Flux + cert-manager + ingress installed.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TX.1** | `ci: add buf lint + breaking GitHub Actions workflow` | `.github/workflows/ci.yml` runs `buf lint` and `buf breaking --against .git#branch=main` on every PR | PR from a branch with a proto break fails CI |
| **TY.1** | `deploy(kind): bootstrap script + Flux install` | `deploy/kind/bootstrap.sh` creates cluster, installs Flux, enables image reflector/automation controllers | Running the script ends with `flux check` clean |
| **TY.2** | `deploy(flux): cluster root kustomization for local` | `deploy/flux/clusters/local/flux-system/` + empty `infra/` + `apps/` kustomizations reconciled | `flux get kustomizations` shows `infra` + `apps` Ready |
| **TY.3** | `deploy(flux): cert-manager + ingress-nginx HelmReleases` | HelmReleases under `deploy/flux/infra/` for cert-manager and ingress-nginx | `kubectl get pods -n cert-manager` and `-n ingress-nginx` all Ready |

**Checkpoint CF1:** `./deploy/kind/bootstrap.sh` exits 0, `flux get
kustomizations` shows everything Ready, `kubectl get pods -A` is clean.

---

## Phase 1 — common-grpc (Slice 1)

Goal: shared library consumable by the three services.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T1.1** | `common-grpc: scaffold module + parent pom` | Parent pom + `common-grpc` child build with `mvn -pl common-grpc -am package`; CI workflow now runs `mvn verify` | CI goes green with mvn step |
| **T1.2** | `common-grpc: JwtAuthInterceptor` | Unsigned JWT → `UNAUTHENTICATED`; valid → principal on gRPC `Context` | JUnit with mock JWKS |
| **T1.3** | `common-grpc: GrpcExceptionAdvice` | `NotFoundException → NOT_FOUND`, `IllegalTransitionException → FAILED_PRECONDITION` | JUnit |
| **T1.4** | `common-grpc: MtlsClientConfig helper` | Loads cert/key/ca from classpath, returns `SslContext` | JUnit with test fixtures |

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

## Phase 2c — Shipment deployment to kind (new, deployment track)

Goal: Shipment + its infra (Postgres, Kafka, Debezium) reconcile under
Flux in kind; GH Actions publishes an image to GHCR on every main commit.

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TY.S1** | `shipment: multi-stage Dockerfile (JLink JRE)` | `docker build` succeeds, image < 250 MB | Local build |
| **TY.S2** | `ci: shipment image build + push to GHCR` | Workflow pushes `ghcr.io/<owner>/cargo-shipment:<sha>` on main + `:<tag>` on release tags | Inspect package in GHCR |
| **TY.S3** | `deploy(helm): shipment chart` | `deploy/helm/shipment/` with Deployment, Service, ConfigMap, values for DB + Kafka endpoints | `helm template` + `helm lint` clean |
| **TY.S4** | `deploy(flux): Postgres HelmRelease for shipment` | Bitnami Postgres HelmRelease in `deploy/flux/infra/postgres-shipment.yaml`, isolated namespace | Reconciled, `psql` port-forward works |
| **TY.S5** | `deploy(flux): Strimzi Kafka operator + cluster` | Strimzi operator HelmRelease + `Kafka` CR under `deploy/flux/infra/kafka/` | Kafka cluster Ready |
| **TY.S6** | `deploy(flux): Kafka Connect + Debezium connector` | Kafka Connect HelmRelease with Debezium plugin; `KafkaConnector` CR pointing at shipment outbox | `connector/status` = RUNNING |
| **TY.S7** | `deploy(flux): shipment HelmRelease` | `deploy/flux/apps/shipment.yaml` HelmRelease pinned to `:<sha>`, reads DB creds from secret | `flux reconcile helmrelease shipment` Ready |

**Checkpoint CY1:** `make cluster-up` + merging a PR → kind cluster with
Shipment + deps Ready. `kubectl port-forward svc/shipment 9090` +
`grpcurl CreateShipment` + `kafka-console-consumer` on
`cargo.shipment.events` shows the event.

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
| **T6.1** | `tracking: ShipmentClient w/ mTLS from common-grpc` | Calls Shipment from Tracking | IT w/ both services |
| **T6.2** | `tracking: sync enrichment fallback in GetTracking` | Missing RM → single Shipment call → cached | IT |

**Checkpoint C6:** sync path tested; Tracking feature-complete.

---

## Phase 3d — Tracking deployment to kind

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TY.T1** | `tracking: multi-stage Dockerfile` | Image builds, < 250 MB | Local |
| **TY.T2** | `ci: tracking image build + push to GHCR` | `ghcr.io/<owner>/cargo-tracking:<sha>` on main | GHCR |
| **TY.T3** | `deploy(helm): tracking chart` | `helm lint` clean | Template |
| **TY.T4** | `deploy(flux): Postgres HelmRelease for tracking` | Isolated `postgres-tracking` | Reconciled |
| **TY.T5** | `deploy(flux): tracking HelmRelease` | `apps/tracking.yaml`, wired to Kafka from CY1 | Reconciled |

**Checkpoint CY2:** tracking pod consumes `cargo.shipment.events` from
the in-cluster Kafka; port-forward `StreamTracking` returns events.

---

## Phase 4 — Notification Service (Slice 7)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T7.1** | `notification: scaffold module + HealthCheck RPC` | Boots, healthcheck returns OK | grpcurl |
| **T7.2** | `notification: ShipmentEventsListener → NOTIFY log` | One `NOTIFY shipment.<type> id=<id>` line per event; offset commits after log | IT w/ appender capture |
| **T7.3** | `notification: StalledCargoDetector scheduled job` | Logs `NOTIFY tracking.stalled id=<id>` for stale entries | Unit + IT |

**Checkpoint C7:** notifications observable under IT.

---

## Phase 4b — Notification deployment to kind

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **TY.N1** | `notification: Dockerfile` | Image builds | Local |
| **TY.N2** | `ci: notification image build + push to GHCR` | `cargo-notification:<sha>` on main | GHCR |
| **TY.N3** | `deploy(helm): notification chart` | Stateless, no DB; values reference shared Kafka | `helm lint` |
| **TY.N4** | `deploy(flux): notification HelmRelease` | `apps/notification.yaml` | Reconciled |

**Checkpoint CY3:** `kubectl logs -l app=notification` shows NOTIFY
lines triggered by Shipment RPCs in kind.

---

## Phase 5 — Edge + security (Slice 8)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T8.1** | `deploy(flux): Keycloak HelmRelease + cargo realm import` | Bitnami Keycloak chart, realm ConfigMap mounted, `cargo-client` confidential client + test user | `curl .../.well-known/openid-configuration` |
| **T8.2** | `deploy(flux): Envoy HelmRelease + gRPC-Web filter` | Envoy Deployment + Ingress exposing gRPC-Web with TLS termination, routes to shipment + tracking | Browser grpc-web client hits `GetShipment` |
| **T8.3** | `deploy(flux): cert-manager CA issuer + service Certificates` | `ClusterIssuer` + per-service `Certificate` resources; mTLS secrets mounted into pods | `openssl s_client` shows client cert presented |
| **T8.4** | `services: enable JWT requirement across all three` | Interceptor from T1.2 active under a kind profile; anonymous call → `UNAUTHENTICATED` | Smoke test w/ + w/o token |

**Checkpoint CY4:** edge + auth + mTLS all verified in kind. (Replaces
old C8.)

---

## Phase 6 — E2E demo + observability (Slice 9)

| Task | PR title | Acceptance | Verify |
|---|---|---|---|
| **T9.1** | `scripts: make demo against kind cluster` | Script port-forwards, obtains Keycloak token, creates shipment, pushes 5 tracking events, streams, asserts NOTIFY log | `make demo` exits 0 |
| **T9.2** | `services: JSON structured logging + shipment_id MDC` | Log lines parseable; MDC present on every request-path line | `kubectl logs` grep |
| **T9.3** | `deploy(flux): Prometheus + Grafana + ServiceMonitors` | Prometheus scrapes `/actuator/prometheus`; sample dashboard ConfigMap | Grafana shows shipment QPS |

**Checkpoint C9 (SHIP):** all umbrella AC from SPEC §5 ticked. Tag
`v0.1.0`. GH Actions release workflow builds tagged images.

---

## Parallelization

- **After CY1**, two PR streams run independently:
  - **Stream A** — Phase 3 (Tracking: T4→T6 then TY.T*)
  - **Stream B** — Phase 4 (Notification: T7→TY.N*)
- They rejoin at **CY4** (Phase 5 needs both services deployed).
- **Track X (CI)** is not a stream — TX.1 is one-shot at Phase 0 and
  auto-extends as modules are added (T1.1 adds mvn verify, TY.S2 adds
  per-service image builds).

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

- **Strimzi + Debezium on kind** — resource-hungry. Mitigation: single
  broker, single connect worker, request limits tuned for laptop.
- **Image pull in kind** — `kind load docker-image` vs GHCR. Default to
  GHCR pull (prod-shaped); `kind load` is a dev fallback.
- **Flux reconcile time** — initial reconcile of 6+ HelmReleases is slow.
  Mitigation: document `flux reconcile --with-source` as the fast path.
- **Debezium IT flakiness (T3.4)** — slow connector startup. Mitigation:
  longer timeout + retry on event assertion.
- **Streaming back-pressure (T5.3)** — naive multicast can block.
  Mitigation: `onBackpressureBuffer` with cap + drop-oldest.
- **Keycloak realm import** — brittle across versions. Mitigation: pin
  Keycloak image tag in HelmRelease values.
- **mTLS cert wiring (T8.3)** — easy to mis-mount. Mitigation: one
  cert-manager `ClusterIssuer`, per-service `Certificate`, consistent
  secret names.

---

## Deferred / Optional (not in v0.1.0)

- Flux `ImageUpdateAutomation` — for training, bump image tags in PRs
  manually. Add later if it saves time.
- Cosign image signing — nice for prod, not blocking.
- Multi-tenant data isolation
- Real email/SMS/push delivery
- Production Kafka sizing or HA Postgres
- Fine-grained RBAC beyond "authenticated"
- Horizontal scaling of streaming subscribers
