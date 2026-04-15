# spring-grpc-microservices

Cargo tracking platform — Spring Boot + gRPC microservices with Debezium CDC,
Kafka, Envoy gRPC-Web, Keycloak OAuth2, and mTLS. Deployed to a 3-node
Hetzner k3s cluster provisioned by
[`hetzner-k3s`](https://github.com/vitobotta/hetzner-k3s) and reconciled
by Flux CD.

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
deploy/
  hetzner/          hetzner-k3s cluster config (3-node k3s on Hetzner)
  flux/             Flux CD kustomizations (apps + infra)
  helm/             per-service Helm charts
  debezium/         Kafka Connect connector configs (Slice 3)
scripts/            local dev + e2e scripts
SPEC.md             umbrella spec + per-service PRDs
tasks/              implementation plan + checklist
```

## Prerequisites

- JDK 21, Maven 3.9+
- Docker (for building images locally)
- `buf` ≥ 1.60
- `hetzner-k3s` ≥ 2.4 (reads Hetzner Cloud token from `HCLOUD_TOKEN`)
- `kubectl` + `flux` ≥ 2.2
- `helm` ≥ 3.14

## Run locally

```sh
make lint           # buf lint + mvn validate + helm lint
make proto          # buf generate Java stubs
make test           # mvn verify (unit + Testcontainers IT)
make cluster-up     # hetzner-k3s create + flux bootstrap + reconcile deploy/flux/clusters/local
make cluster-down   # hetzner-k3s delete  ⚠ destroys cloud resources
make demo           # e2e: create shipment → stream tracking → assert notify log
make clean          # wipe gen/ + target/
```

> ⚠ `make cluster-up` provisions real Hetzner nodes (~€17/month on
> default instance sizes). Always `make cluster-down` when you're done
> for the day.

Manual cluster provision (with `HCLOUD_TOKEN` exported):

```sh
hetzner-k3s create --config deploy/hetzner/cluster.yaml
export KUBECONFIG="$PWD/deploy/hetzner/kubeconfig"
kubectl get nodes
flux get kustomizations
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
