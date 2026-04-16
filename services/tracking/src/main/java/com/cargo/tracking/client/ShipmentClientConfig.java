package com.cargo.tracking.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShipmentClientConfig {

    /**
     * Shipment gRPC target — host:port of the Shipment service's
     * plaintext gRPC listener. Defaults to the in-compose DNS name;
     * override with {@code SHIPMENT_GRPC_TARGET=localhost:9090} for
     * local runs.
     */
    @Bean
    public ManagedChannel shipmentChannel(
            @Value("${cargo.tracking.shipment-target:shipment:9090}") String target) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
    }

    @Bean
    public ShipmentClient shipmentClient(ManagedChannel shipmentChannel) {
        return new ShipmentClient(shipmentChannel);
    }
}
