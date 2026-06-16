.PHONY: demo up down logs build certs envoy-local envoy-local-down web-local

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

# ── Local-mode targets ─────────────────────────────────────────────────
# Run an edge service on the host while the rest of the stack stays in
# compose. Workflow:
#   1. make up
#   2. docker compose stop <service>  (envoy / web / shipment / ...)
#   3. start the local replacement (IDE run config, or the targets below)

# Standalone Envoy on :8080 using envoy.local.yaml (plain h2c, upstreams
# pointed at host.docker.internal:9090/9091). Run `docker compose stop envoy`
# first so the port is free.
envoy-local:
	docker run --rm --name cargo-envoy-local \
	  -p 8080:8080 \
	  -v $(PWD)/deploy/envoy/envoy.local.yaml:/etc/envoy/envoy.yaml:ro \
	  envoyproxy/envoy:v1.30-latest

envoy-local-down:
	-docker stop cargo-envoy-local

# Nuxt dev server on :3000 (hot reload). Run `docker compose stop web`
# first so the port is free.
web-local:
	cd web && npm install && npm run dev
