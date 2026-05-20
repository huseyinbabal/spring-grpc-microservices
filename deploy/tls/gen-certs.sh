#!/usr/bin/env bash
#
# Generates a local development CA and per-service mTLS material for the
# cargo stack:
#
#   ca.crt / ca.key            the dev certificate authority
#   shipment.crt / .key        server certs (SAN = service DNS name +
#   tracking.crt / .key        localhost), signed by the CA
#   notification.crt / .key
#   client.crt / client.key    one shared internal client identity used
#                              by tracking -> shipment and Envoy -> services
#
# DEV / TRAINING ONLY. These keys are committed to the repo on purpose
# so `make up` works out of the box with mTLS enabled. Never reuse this
# CA, or these keys, outside the training environment.
#
# Idempotent: re-run is a no-op once ca.crt exists. To rotate, delete
# deploy/tls/*.crt deploy/tls/*.key deploy/tls/*.srl and re-run.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f ca.crt ]; then
  echo "certs already present — delete deploy/tls/*.crt *.key *.srl to regenerate"
  exit 0
fi

DAYS=3650
BITS=2048

# ── Certificate Authority ───────────────────────────────────────────
openssl genpkey -algorithm RSA -pkeyopt "rsa_keygen_bits:${BITS}" -out ca.key
openssl req -x509 -new -key ca.key -sha256 -days "$DAYS" \
  -subj "/O=Cargo Training/CN=Cargo Dev CA" -out ca.crt

# ── Per-service server certs ────────────────────────────────────────
# SAN must match the authority clients use: the compose DNS name (e.g.
# `shipment`) and `localhost` for host-side grpcurl.
gen_server() {
  local name="$1"
  openssl genpkey -algorithm RSA -pkeyopt "rsa_keygen_bits:${BITS}" -out "${name}.key"
  openssl req -new -key "${name}.key" \
    -subj "/O=Cargo Training/CN=${name}" -out "${name}.csr"
  openssl x509 -req -in "${name}.csr" -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days "$DAYS" -sha256 \
    -extfile <(printf 'subjectAltName=DNS:%s,DNS:localhost\nextendedKeyUsage=serverAuth\n' "$name") \
    -out "${name}.crt"
  rm -f "${name}.csr"
}

for svc in shipment tracking notification; do
  gen_server "$svc"
done

# ── Shared internal client cert ─────────────────────────────────────
openssl genpkey -algorithm RSA -pkeyopt "rsa_keygen_bits:${BITS}" -out client.key
openssl req -new -key client.key \
  -subj "/O=Cargo Training/CN=cargo-internal-client" -out client.csr
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -days "$DAYS" -sha256 \
  -extfile <(printf 'extendedKeyUsage=clientAuth\n') \
  -out client.crt
rm -f client.csr

chmod 644 ./*.crt
chmod 600 ./*.key
echo "generated: CA + server certs (shipment, tracking, notification) + client cert"
