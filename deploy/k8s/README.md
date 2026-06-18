# Cargo Tracking Platform — Kubernetes / Flux deployment

Session 07's "from commit to cluster" target: the same 13-service stack
you ran with Docker Compose, now running on a **kind** cluster and
reconciled by **Flux** (GitOps). Operators bring up the stateful
backends; Kustomize delivers our own apps.

```
FluxInstance (Flux Operator)
   └─ GitRepository + Kustomization "flux-system"  →  deploy/k8s/flux
         ├─ infrastructure   (Helm)   CloudNativePG + Strimzi operators
         │      ▼ dependsOn
         ├─ databases         CNPG Clusters (shipment*, tracking) + Strimzi Kafka + topic
         │      ▼ dependsOn
         └─ apps              shipment · tracking · notification · envoy ·
                              keycloak · web · Debezium Connect (+ register Job)
```

`*` shipment DB runs `wal_level=logical` and grants `REPLICATION` to the
app role so Debezium's `pgoutput` plugin can tail the outbox table.

## Layout

| Path | What |
|------|------|
| `kind/kind-config.yaml` | 1-node cluster; host ports 8080 (envoy), 3000 (web), 8180 (keycloak) |
| `flux-instance.yaml` | `FluxInstance` CR — declares Flux version, components, Git sync |
| `flux/` | Flux `Kustomization` CRs: infrastructure → databases → apps |
| `infrastructure/` | `HelmRelease`s for CloudNativePG + Strimzi |
| `databases/` | CNPG `Cluster`s, Strimzi `Kafka` + `KafkaNodePool` + `KafkaTopic` |
| `apps/base/` | Deployments/Services + generators reusing `deploy/{tls,envoy,keycloak}` |
| `apps/overlays/dev/` | namespace `cargo` + image tags (`:main`) + `$imagepolicy` markers |
| `image-automation/` | `ImageRepository` + `ImagePolicy` + `ImageUpdateAutomation` per service |

The TLS Secret, Envoy config and Keycloak realm are **generated from the
exact same files Compose mounts** (`deploy/tls`, `deploy/envoy`,
`deploy/keycloak`) — one source of truth, two delivery mechanisms.

## Prerequisites

- `kind`, `kubectl`, `helm` (the `flux` CLI is **not** required)
- GHCR images `ghcr.io/huseyinbabal/cargo-{shipment,tracking,notification}:main`
  must be **public** (built by `.github/workflows/image-*.yml`), or add an
  `imagePullSecret`.

## Bring-up

Driven by Make targets in the repo-root `Makefile`:

```bash
make k8s-up        # kind cluster + Flux Operator (Helm) + FluxInstance
make k8s-status    # HelmReleases, Kustomizations, CNPG/Kafka, pods
make k8s-down      # kind delete cluster
make k8s-validate  # render every layer locally, no cluster needed
```

`make k8s-up` does three things: creates the kind cluster, installs the
Flux Operator via Helm (**not** the `flux` CLI), and applies the
`FluxInstance`. Flux then reconciles `deploy/k8s/flux` from Git, bringing
up operators → databases → apps in order.

> GitOps note: Flux pulls from the **pushed `main` branch**. Commit and
> push `deploy/k8s/**` before `make k8s-up`, or Flux won't see your
> changes.

## Verifying

```bash
# Flux reconciliation state
kubectl get helmrelease,kustomization -A

# Backends
kubectl get cluster.postgresql.cnpg.io -n cargo       # CNPG
kubectl get kafka,kafkatopic -n cargo                 # Strimzi

# Workloads + the CDC connector registration
kubectl get pods -n cargo
kubectl logs -n cargo job/register-shipment-outbox

# From the host (kind port mappings)
open http://localhost:3000      # web UI
open http://localhost:8180      # keycloak (admin/admin)
# grpc-web edge: http://localhost:8080
```

## Local validation (no cluster)

Kustomize generators reference files outside their dir, so disable the
load restrictor locally (Flux disables it by default):

```bash
kustomize build deploy/k8s/flux
kustomize build deploy/k8s/infrastructure
kustomize build deploy/k8s/databases
kustomize build --load-restrictor=LoadRestrictionsNone deploy/k8s/apps/overlays/dev
```

## Image automation (CD without touching the tag by hand)

The `image-reflector` + `image-automation` controllers close the loop:

```
CI pushes  ghcr.io/.../cargo-shipment:main-20260618T1625   (sortable tag)
   │
   ▼
ImageRepository  scans GHCR tags every 5m
   │
   ▼
ImagePolicy      filters ^main-(\d+)$, picks the newest
   │
   ▼
ImageUpdateAutomation  rewrites the $imagepolicy marker in
                       apps/overlays/dev/kustomization.yaml → commits to main
   │
   ▼
Flux reconciles the new tag onto the cluster
```

- CI tags every `main` build with `main-<UTC timestamp>` (see the
  `image-*.yml` workflows) — moving tags like `:main`/`:latest` are not
  orderable, so the policy ignores them.
- The markers live on the `newTag` lines in `apps/overlays/dev` (all four
  images, including web).
- **Write credentials required**: the automation pushes commits back to
  `main`, so the `flux-system` GitRepository needs a write-capable secret —
  see `sync.pullSecret` in `flux-instance.yaml`. Without it, scanning and
  policy selection still work, but the commit/push step fails.

```bash
# Inspect what the controllers see
flux get image repository
flux get image policy
flux get image update
```

## Known gaps / notes

- **Multi-arch images**: services + web are built for `linux/amd64` and
  `linux/arm64` (Apple Silicon kind nodes need arm64). The arm64 layer is
  emulated via QEMU in CI, so image builds are slower.
- **Observability** (Loki/Tempo/Prometheus/Grafana) is **Session 08**.
  Services keep their `OTEL_EXPORTER_OTLP_ENDPOINT` set; until Tempo
  exists, OTLP export is a harmless no-op.
- **Debezium** runs as a plain Connect Deployment + idempotent
  registration Job (not a Strimzi `KafkaConnect`), to avoid building a
  custom Connect image in a cluster with no push registry.
- **Helm chart versions** for the operators are floating ranges; pin them
  for anything beyond a demo.
- **Image promotion**: `apps/overlays/dev` sets `:main`. A staging/prod
  overlay is just a different `newTag` + replica patch — same image bytes.
