.PHONY: demo up down logs build certs

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
