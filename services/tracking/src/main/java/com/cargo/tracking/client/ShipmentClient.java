package com.cargo.tracking.client;

import com.cargo.shipment.v1.GetShipmentRequest;
import com.cargo.shipment.v1.GetShipmentResponse;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thin blocking gRPC client for the Shipment Service, used by the
 * Tracking Service's sync-enrichment fallback in GetTracking (T6.2).
 *
 * <p>Cross-service traffic inside the compose stack is <em>plaintext</em>
 * intentionally — mTLS is deferred past v0.1.0 (see
 * {@code tasks/todo.md}). The channel target is injected from the
 * {@code SHIPMENT_GRPC_TARGET} env var so the same binary runs inside
 * compose (target = {@code shipment:9090}) and from a local run
 * against {@code localhost:9090}.
 */
public class ShipmentClient {

    private static final Logger log = LoggerFactory.getLogger(ShipmentClient.class);

    private final ManagedChannel channel;
    private final ShipmentServiceGrpc.ShipmentServiceBlockingStub stub;

    public ShipmentClient(ManagedChannel channel) {
        this.channel = channel;
        this.stub = ShipmentServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Looks up a shipment by id. Returns {@link Optional#empty()} when
     * Shipment replies NOT_FOUND or the RPC fails — callers should
     * treat those equivalently so a Shipment outage never breaks
     * GetTracking.
     */
    public Optional<GetShipmentResponse> findShipmentById(String shipmentId) {
        try {
            GetShipmentResponse response = stub
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .getShipment(GetShipmentRequest.newBuilder().setId(shipmentId).build());
            return Optional.of(response);
        } catch (StatusRuntimeException e) {
            log.debug("shipment lookup failed for {}: {}", shipmentId, e.getStatus());
            return Optional.empty();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(3, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}
