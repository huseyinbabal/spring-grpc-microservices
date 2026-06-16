# spring-grpc-microservices

Cargo tracking platform — Spring Boot + gRPC microservices with Debezium CDC,
Kafka, Envoy gRPC-Web, and Keycloak OAuth2. The whole stack (shared
infra + three services + web UI) runs locally via a single `compose.yaml`.

See [`SPEC.md`](SPEC.md) for the umbrella spec and per-service PRDs, and
[`tasks/plan.md`](tasks/plan.md) for the PR-sized task breakdown.

## Repo layout

```
proto/              buf module — contract-first gRPC APIs
common-grpc/        shared interceptor + client library
services/
  shipment/         Shipment Service (CRUD + outbox CDC)
  tracking/         Tracking Service (ingest + streaming + read model)
  notification/     Notification Service (event listener + stalled detector)
web/                Nuxt 3 gRPC-Web frontend (ConnectRPC + @bufbuild/protobuf)
compose.yaml        local dev stack — 13 containers, one `make up`
deploy/
  debezium/         Kafka Connect connector configs
  envoy/            Envoy gRPC-Web proxy config
  keycloak/         Keycloak realm import (cargo realm + demo user)
  loki/             Loki log aggregation config
  promtail/         Promtail Docker log scraper config
  grafana/          Grafana provisioning (Loki datasource + dashboard)
scripts/            demo script
tasks/              implementation plan + checklist
SPEC.md             umbrella spec + per-service PRDs
```

## Prerequisites

- JDK 21, Maven 3.9+
- Docker Engine 27+ with Compose v2
- `buf` >= 1.47 (for proto generation)
- `grpcurl` (for CLI demo)

## Run locally

```sh
make up          # docker compose up -d --build (builds + starts all 13 containers)
make demo        # e2e: create shipment → report location → get tracking → check Kafka + notification logs
make logs        # docker compose logs -f
make down        # docker compose down -v
make build       # mvn -B -ntp verify
```

**Web UI:** http://localhost:3000 (Nuxt gRPC-Web app)
**Grafana:** http://localhost:3001 (Loki logs, admin/admin)
**Keycloak:** http://localhost:8180 (cargo realm, admin/admin)
**Envoy:** http://localhost:8080 (gRPC-Web proxy)

## Local-mode (debug a service from the IDE)

Every service ships in `compose.yaml`. To swap one out for a host-side
copy (IDE debug, hot reload) while the rest of the stack stays in
compose:

1. `make up`
2. `docker compose stop <service>` to free the port — `shipment`,
   `tracking`, `notification`, `envoy`, or `web`
3. Start the host-side replacement:
   - **shipment / tracking / notification** — IntelliJ run config in `.run/`
   - **envoy** — `make envoy-local` (uses `deploy/envoy/envoy.local.yaml`,
     plain h2c, upstreams = `host.docker.internal:9090/9091`)
   - **web** — `make web-local` (`npm install && nuxt dev`)

Each replacement binds the same port the compose container did, so the
rest of the stack keeps working unchanged.

## Status

- [x] Slice 0 — walking skeleton
- [x] Slice 1 — `common-grpc` shared library (JWT interceptor + error advice)
- [x] Slice 2 — Shipment Service CRUD (5 RPCs)
- [x] Slice 3 — Shipment outbox + Debezium CDC
- [x] Slice 4 — Tracking ingest + Kafka consumer (read model)
- [x] Slice 5 — Tracking server streaming + backpressure buffer
- [x] Slice 6 — Sync enrichment fallback (Shipment RPC)
- [x] Slice 7 — Notification Service (event listener + stalled cargo detector)
- [x] Slice 8 — Envoy gRPC-Web + Keycloak OAuth2
- [x] Slice 9 — Demo script + Makefile
- [x] Logging — Loki + Promtail + Grafana
- [x] Web UI — Nuxt 3 gRPC-Web frontend
