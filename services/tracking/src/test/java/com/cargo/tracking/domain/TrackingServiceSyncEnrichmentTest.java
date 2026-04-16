package com.cargo.tracking.domain;

import com.cargo.common.grpc.error.NotFoundException;
import com.cargo.shipment.v1.GetShipmentResponse;
import com.cargo.shipment.v1.Shipment;
import com.cargo.shipment.v1.ShipmentStatus;
import com.cargo.tracking.client.ShipmentClient;
import com.cargo.tracking.persistence.ShipmentReadModelEntity;
import com.cargo.tracking.persistence.ShipmentReadModelRepository;
import com.cargo.tracking.persistence.TrackingEventRepository;
import com.cargo.tracking.stream.TrackingEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackingServiceSyncEnrichmentTest {

    @Mock TrackingEventRepository events;
    @Mock ShipmentReadModelRepository readModel;
    @Mock ShipmentClient shipmentClient;

    private TrackingService service;

    @BeforeEach
    void setUp() {
        service = new TrackingService(events, readModel, new TrackingEventBus(), shipmentClient);
    }

    @Test
    void get_tracking_returns_cached_row_without_consulting_shipment() {
        UUID shipmentId = UUID.randomUUID();
        ShipmentReadModelEntity cached = new ShipmentReadModelEntity(
                shipmentId, "CT-2026-000001", "DHL",
                com.cargo.tracking.domain.ShipmentStatus.IN_TRANSIT);
        when(readModel.findById(shipmentId)).thenReturn(Optional.of(cached));

        ShipmentReadModelEntity result = service.getTracking(shipmentId);

        assertThat(result).isSameAs(cached);
        // ShipmentClient is not touched when the cache hits.
        org.mockito.Mockito.verifyNoInteractions(shipmentClient);
    }

    @Test
    void get_tracking_falls_back_to_shipment_rpc_on_cache_miss() {
        UUID shipmentId = UUID.randomUUID();
        when(readModel.findById(shipmentId)).thenReturn(Optional.empty());
        when(shipmentClient.findShipmentById(shipmentId.toString()))
                .thenReturn(Optional.of(GetShipmentResponse.newBuilder()
                        .setShipment(Shipment.newBuilder()
                                .setId(shipmentId.toString())
                                .setTrackingCode("CT-2026-000042")
                                .setCarrier("UPS")
                                .setStatus(ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT)
                                .build())
                        .build()));

        ShipmentReadModelEntity result = service.getTracking(shipmentId);

        assertThat(result.getShipmentId()).isEqualTo(shipmentId);
        assertThat(result.getTrackingCode()).isEqualTo("CT-2026-000042");
        assertThat(result.getCarrier()).isEqualTo("UPS");
        assertThat(result.getStatus()).isEqualTo(
                com.cargo.tracking.domain.ShipmentStatus.IN_TRANSIT);
    }

    @Test
    void get_tracking_throws_not_found_when_shipment_also_unknown() {
        UUID shipmentId = UUID.randomUUID();
        when(readModel.findById(shipmentId)).thenReturn(Optional.empty());
        when(shipmentClient.findShipmentById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTracking(shipmentId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(shipmentId.toString());
    }

    @Test
    void get_tracking_throws_not_found_when_shipment_rpc_returns_empty_envelope() {
        UUID shipmentId = UUID.randomUUID();
        when(readModel.findById(shipmentId)).thenReturn(Optional.empty());
        // Upstream replied but with an empty GetShipmentResponse — treat
        // as a miss, not as an enriched hit.
        when(shipmentClient.findShipmentById(any()))
                .thenReturn(Optional.of(GetShipmentResponse.newBuilder().build()));

        assertThatThrownBy(() -> service.getTracking(shipmentId))
                .isInstanceOf(NotFoundException.class);
    }
}
