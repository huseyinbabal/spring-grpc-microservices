package com.cargo.tracking.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShipmentClientConfig {

    /**
     * Shipment gRPC target — host:port of the Shipment service's
     * plaintext gRPC listener. Defaults to the in-compose DNS name;
     * override with {@code SHIPMENT_GRPC_TARGET=localhost:9090} for
     * local runs. Wrapped with {@link ObservationGrpcClientInterceptor}
     * so every unary/server-streaming call becomes a child span of the
     * caller's observation and propagates W3C traceparent to shipment.
     */
    @Bean
    public ManagedChannel shipmentChannel(
            @Value("${cargo.tracking.shipment-target:shipment:9090}") String target,
            ObservationRegistry observationRegistry) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .intercept(new ObservationGrpcClientInterceptor(observationRegistry))
                .build();
    }

    @Bean
    public ShipmentClient shipmentClient(ManagedChannel shipmentChannel) {
        return new ShipmentClient(shipmentChannel);
    }
}
