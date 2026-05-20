#!/usr/bin/env bash
set -euo pipefail

# The cargo gRPC services require mutual TLS. grpcurl verifies the server
# cert against the dev CA and presents the shared internal client cert.
TLS="$(cd "$(dirname "$0")/.." && pwd)/deploy/tls"
MTLS=(-cacert "$TLS/ca.crt" -cert "$TLS/client.crt" -key "$TLS/client.key")

echo "=== Cargo Tracking Platform — Demo (mTLS) ==="
echo ""
echo "1. Creating a shipment via Shipment Service (port 9090)..."
RESPONSE=$(grpcurl "${MTLS[@]}" -d '{
  "origin": {"line1":"Alexanderplatz 1","city":"Berlin","country":"DE","postalCode":"10178"},
  "destination": {"line1":"Rue de Rivoli 1","city":"Paris","country":"FR","postalCode":"75001"},
  "carrier": "DHL",
  "weightKg": 7.25
}' localhost:9090 cargo.shipment.v1.ShipmentService/CreateShipment)

echo "$RESPONSE"
SHIPMENT_ID=$(echo "$RESPONSE" | grep -o '"id": "[^"]*"' | head -1 | cut -d'"' -f4)
echo ""
echo "   Shipment ID: $SHIPMENT_ID"

echo ""
echo "2. Reporting a location via Tracking Service (port 9091)..."
grpcurl "${MTLS[@]}" -d "{
  \"event\": {
    \"shipmentId\": \"$SHIPMENT_ID\",
    \"lat\": 52.52,
    \"lng\": 13.40,
    \"source\": \"demo-script\"
  }
}" localhost:9091 cargo.tracking.v1.TrackingService/ReportLocation

echo ""
echo "3. Getting tracking snapshot..."
grpcurl "${MTLS[@]}" -d "{\"shipmentId\": \"$SHIPMENT_ID\"}" \
  localhost:9091 cargo.tracking.v1.TrackingService/GetTracking

echo ""
echo "4. Checking Kafka topic for outbox event..."
docker exec cargo-kafka kafka-console-consumer \
  --bootstrap-server localhost:9094 \
  --topic cargo.shipment.events \
  --from-beginning \
  --timeout-ms 5000 \
  --max-messages 1 2>/dev/null || echo "   (no events found or Kafka not running)"

echo ""
echo "5. Checking notification service logs..."
docker logs cargo-notification 2>&1 | grep "NOTIFY" | tail -5 || echo "   (no NOTIFY lines yet)"

echo ""
echo "6. Scraping gRPC server metrics from Shipment (management port 8091)..."
curl -fsS http://localhost:8091/actuator/prometheus 2>/dev/null \
  | grep -E '^grpc_server' | head -5 || echo "   (no gRPC metrics yet)"

echo ""
echo "=== Demo complete ==="
