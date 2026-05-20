package com.cargo.tracking.client;

import com.cargo.shipment.v1.GetShipmentRequest;
import com.cargo.shipment.v1.GetShipmentResponse;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thin blocking gRPC client for the Shipment Service, used by the
 * Tracking Service's sync-enrichment fallback in GetTracking.
 *
 * <p><b>Fault tolerance.</b> {@link #findShipmentById} is guarded by a
 * Resilience4j circuit breaker (instance {@code shipment}, configured in
 * {@code application.yml}). The layering is deliberate:
 *
 * <ul>
 *   <li><b>Deadline</b> — every call is bounded to 2s
 *       ({@code withDeadlineAfter}); the channel's service config also
 *       carries a 3s per-method timeout.</li>
 *   <li><b>Retries</b> — transient {@code UNAVAILABLE}/
 *       {@code RESOURCE_EXHAUSTED} failures are retried transparently by
 *       the channel (see {@code grpc/shipment-service-config.json}).</li>
 *   <li><b>Circuit breaker</b> — if retries keep failing, the breaker
 *       opens and subsequent calls short-circuit straight to the
 *       fallback instead of piling up on a sick dependency.</li>
 *   <li><b>Fallback</b> — {@link #findShipmentByIdFallback} degrades
 *       gracefully to {@code Optional.empty()} so a Shipment outage
 *       surfaces as NOT_FOUND from GetTracking rather than an error.</li>
 * </ul>
 *
 * <p>A {@code NOT_FOUND} reply is a definitive answer, not a fault — it
 * is returned as {@code Optional.empty()} <em>without</em> tripping the
 * breaker. Cross-service traffic is plaintext inside compose; TLS/mTLS
 * is configured on the channel in the deployed stack.
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
     * Shipment replies {@code NOT_FOUND}. Any other gRPC failure is
     * rethrown so the circuit breaker records it; once the breaker
     * opens, calls route straight to {@link #findShipmentByIdFallback}.
     */
    @CircuitBreaker(name = "shipment", fallbackMethod = "findShipmentByIdFallback")
    public Optional<GetShipmentResponse> findShipmentById(String shipmentId) {
        try {
            GetShipmentResponse response = stub
                    .withDeadlineAfter(2, TimeUnit.SECONDS)
                    .getShipment(GetShipmentRequest.newBuilder().setId(shipmentId).build());
            return Optional.of(response);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                // Definitive "no such shipment" — a normal answer, not a
                // fault. Return empty WITHOUT letting the breaker count it.
                return Optional.empty();
            }
            // Transient/unknown fault — rethrow so the circuit breaker
            // records it; the fallback turns it into Optional.empty().
            throw e;
        }
    }

    /**
     * Circuit-breaker fallback for {@link #findShipmentById}. Invoked when
     * the RPC fails with a non-NOT_FOUND status or when the breaker is
     * open. Degrades to an empty result so a Shipment outage never breaks
     * GetTracking.
     */
    @SuppressWarnings("unused")
    private Optional<GetShipmentResponse> findShipmentByIdFallback(String shipmentId, Throwable t) {
        log.warn("shipment lookup degraded for {} — breaker open or RPC failed: {}",
                shipmentId, t.toString());
        return Optional.empty();
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
