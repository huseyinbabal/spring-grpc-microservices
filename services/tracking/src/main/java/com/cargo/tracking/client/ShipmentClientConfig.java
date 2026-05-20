package com.cargo.tracking.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Builds the long-lived {@link ManagedChannel} the Tracking service uses
 * to call Shipment for the sync-enrichment fallback.
 *
 * <p>This bean is the reference for production-grade channel
 * configuration — it is intentionally explicit so the training can walk
 * through every knob:
 *
 * <ul>
 *   <li><b>Mutual TLS</b> — the channel presents the internal client
 *       cert and trusts only the cargo dev CA; the Shipment server
 *       requires the client cert in turn. Disabled in the test profile
 *       ({@code cargo.tracking.tls.enabled=false}) where the transport
 *       is a loopback/in-process channel with no wire.</li>
 *   <li><b>Client-side load balancing</b> — the {@code dns:///} target
 *       makes gRPC's DNS resolver return <em>every</em> A record;
 *       {@code round_robin} spreads calls across them. In Kubernetes the
 *       target must be a <em>headless</em> Service so each pod is its
 *       own A record — a plain ClusterIP would pin every RPC to one
 *       pod.</li>
 *   <li><b>HTTP/2 keepalive</b> — idle connections are pinged so
 *       half-open sockets are detected and recycled.</li>
 *   <li><b>Idle timeout</b> — a connection with no RPCs for 5 min is
 *       parked; the next call transparently re-establishes it.</li>
 *   <li><b>Transparent retries</b> — {@link #loadServiceConfig} loads a
 *       gRPC service config JSON whose {@code retryPolicy} retries
 *       transient {@code UNAVAILABLE}/{@code RESOURCE_EXHAUSTED}
 *       failures with exponential backoff.</li>
 * </ul>
 *
 * <p>Every call is wrapped with {@link ObservationGrpcClientInterceptor}
 * so it becomes a child span and propagates W3C {@code traceparent}.
 */
@Configuration
public class ShipmentClientConfig {

    /** gRPC service config — retry policy + per-method timeout. */
    private static final String SERVICE_CONFIG = "grpc/shipment-service-config.json";

    /**
     * Shipment gRPC target. Defaults to the {@code dns:///} scheme so the
     * DNS resolver + {@code round_robin} balancer see every backend A
     * record. Override with {@code SHIPMENT_GRPC_TARGET}.
     */
    @Bean
    public ManagedChannel shipmentChannel(
            @Value("${cargo.tracking.shipment-target:dns:///shipment:9090}") String target,
            @Value("${cargo.tracking.tls.enabled:true}") boolean tlsEnabled,
            @Value("${cargo.tracking.tls.dir:/etc/cargo/tls}") String tlsDir,
            ObservationRegistry observationRegistry,
            ObjectMapper objectMapper) throws IOException {

        Map<String, ?> serviceConfig = loadServiceConfig(objectMapper);

        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target)
                .defaultLoadBalancingPolicy("round_robin")
                // HTTP/2 keepalive: ping an idle connection every 30s and
                // drop it if no ack arrives within 5s. keepAliveWithoutCalls
                // keeps pinging even with zero in-flight RPCs.
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(5, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                // Park the transport after 5 min idle; the next RPC re-dials.
                .idleTimeout(5, TimeUnit.MINUTES)
                // Transparent client retries, governed by retryPolicy in
                // shipment-service-config.json.
                .enableRetry()
                .defaultServiceConfig(serviceConfig)
                .intercept(new ObservationGrpcClientInterceptor(observationRegistry));

        if (tlsEnabled) {
            // Mutual TLS: present the internal client cert and trust only
            // certificates signed by the cargo dev CA. The server cert's
            // SAN must match the target authority (e.g. `shipment`).
            SslContext sslContext = GrpcSslContexts.forClient()
                    .trustManager(new File(tlsDir, "ca.crt"))
                    .keyManager(new File(tlsDir, "client.crt"), new File(tlsDir, "client.key"))
                    .build();
            builder.sslContext(sslContext);
        } else {
            builder.usePlaintext();
        }
        return builder.build();
    }

    /**
     * Parses {@code grpc/shipment-service-config.json} into the
     * {@code Map} shape gRPC's {@code defaultServiceConfig} expects.
     *
     * <p>gRPC's service-config validator only accepts {@link Double} for
     * JSON numbers and rejects {@code Integer}/{@code Long} outright, so
     * the tree is walked with every number widened to {@code Double}
     * (a plain {@code readValue} would decode {@code "maxAttempts": 4}
     * as an {@code Integer} and fail channel creation).
     */
    private static Map<String, ?> loadServiceConfig(ObjectMapper objectMapper) throws IOException {
        try (InputStream in = new ClassPathResource(SERVICE_CONFIG).getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, ?> config = (Map<String, ?>) toGrpcValue(objectMapper.readTree(in));
            return config;
        }
    }

    /**
     * Converts a Jackson node into the plain Map/List/String/Double/
     * Boolean/null structure {@code defaultServiceConfig} requires,
     * widening every JSON number to {@link Double}.
     */
    private static Object toGrpcValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), toGrpcValue(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(child -> list.add(toGrpcValue(child)));
            return list;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    @Bean
    public ShipmentClient shipmentClient(ManagedChannel shipmentChannel) {
        return new ShipmentClient(shipmentChannel);
    }
}
