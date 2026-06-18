.PHONY: demo up down logs build certs \
	k8s-up k8s-down k8s-status k8s-validate

# ── Docker Compose (local dev) ───────────────────────────────────────

# Generate the local mTLS CA + per-service certs (idempotent — a no-op
# once deploy/tls/ca.crt exists).
certs:
	@bash deploy/tls/gen-certs.sh

up: certs
	docker compose up -d --build

down:
	docker compose down -v

logs:
	docker compose logs -f

demo:
	@bash scripts/demo.sh

build:
	mvn -B -ntp verify

# ── kind + Flux (GitOps) ─────────────────────────────────────────────
# See deploy/k8s/README.md. Flux reconciles the pushed `main` branch, so
# commit + push deploy/k8s/** before `k8s-up`.

KIND_CLUSTER := cargo

# Create the kind cluster, install the Flux Operator (Helm, not the flux
# CLI), and hand it the FluxInstance. Flux then brings up operators ->
# databases -> apps from Git.
k8s-up:
	@lsof -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1 \
		&& { echo "!! something already on :8080 — free it first"; exit 1; } || true
	kind create cluster --config deploy/k8s/kind/kind-config.yaml
	helm install flux-operator \
		oci://ghcr.io/controlplaneio-fluxcd/charts/flux-operator \
		-n flux-system --create-namespace --wait
	kubectl apply -f deploy/k8s/flux-instance.yaml
	@echo "Flux is reconciling from Git. Watch: make k8s-status"

k8s-down:
	kind delete cluster --name $(KIND_CLUSTER)

k8s-status:
	kubectl get helmrelease,kustomization -A
	@echo "---"
	kubectl get cluster.postgresql.cnpg.io,kafka,kafkatopic,pods -n cargo

# Render every layer locally (no cluster). Flux disables the load
# restrictor by default; we pass it explicitly for the apps generators.
k8s-validate:
	kustomize build deploy/k8s/flux >/dev/null
	kustomize build deploy/k8s/infrastructure >/dev/null
	kustomize build deploy/k8s/databases >/dev/null
	kustomize build --load-restrictor=LoadRestrictionsNone deploy/k8s/apps/overlays/dev >/dev/null
	@echo "all kustomize layers build OK"
