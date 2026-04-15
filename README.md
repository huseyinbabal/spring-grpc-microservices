# spring-grpc-microservices

Cargo tracking platform — Spring Boot + gRPC microservices with Debezium CDC,
Kafka, Envoy gRPC-Web, and Keycloak OAuth2. The whole stack (shared
infra + three services) runs locally via a single `compose.yaml`.
Kubernetes deployment is deferred until after v0.1.0.

See [`SPEC.md`](SPEC.md) for the umbrella spec and per-service PRDs, and
[`tasks/plan.md`](tasks/plan.md) for the PR-sized task breakdown.

## Repo layout

```
proto/              buf module — contract-first gRPC APIs
common-grpc/        shared interceptor + client library (Slice 1)
services/
  shipment/         Shipment Service (Slices 2–3)
  tracking/         Tracking Service (Slices 4–5)
  notification/     Notification Service (Slice 7)
compose.yaml        # local dev / e2e deployment stack (Kafka, services, ...)
deploy/
  debezium/         Kafka Connect connector configs (Slice 3)
scripts/            local dev + e2e scripts
SPEC.md             umbrella spec + per-service PRDs
tasks/              implementation plan + checklist
```

## Prerequisites

- JDK 21, Maven 3.9+
- Docker Engine 27+ with Compose v2
- `buf` ≥ 1.60

## Run locally

```sh
make lint        # buf lint + mvn validate
make proto       # buf generate Java stubs
make test        # mvn verify (unit + Testcontainers IT)
make up          # docker compose up -d
make down        # docker compose down
make demo        # e2e: create shipment → stream tracking → assert notify log
make clean       # wipe gen/ + target/
```

Manual stack startup:

```sh
docker compose up -d
docker compose ps
docker compose logs -f kafka
```

## Status

- [x] Slice 0 — walking skeleton (this commit)
- [ ] Slice 1 — `common-grpc` shared library
- [ ] Slice 2 — Shipment Service CRUD
- [ ] Slice 3 — Shipment outbox + Debezium
- [ ] Slice 4 — Tracking ingest + consumers
- [ ] Slice 5 — Tracking server streaming
- [ ] Slice 6 — Sync enrichment
- [ ] Slice 7 — Notification Service
- [ ] Slice 8 — Envoy + Keycloak + mTLS
- [ ] Slice 9 — E2E demo + observability
