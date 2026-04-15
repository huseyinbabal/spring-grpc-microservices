package com.cargo.shipment.api;

import com.cargo.shipment.v1.ShipmentServiceGrpc;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC adapter for the Shipment Service. All RPCs fall through to the
 * {@code ImplBase} default — which returns {@code UNIMPLEMENTED} — until
 * the follow-up tasks in Phase 2a wire real handlers:
 *
 * <ul>
 *   <li>T2.4 — {@code CreateShipment}</li>
 *   <li>T2.5 — {@code GetShipment}</li>
 *   <li>T2.6 — {@code ListShipments}</li>
 *   <li>T2.7 — {@code UpdateShipmentStatus}</li>
 *   <li>T2.8 — {@code CancelShipment}</li>
 * </ul>
 */
@GrpcService
public class ShipmentGrpcService extends ShipmentServiceGrpc.ShipmentServiceImplBase {
}
