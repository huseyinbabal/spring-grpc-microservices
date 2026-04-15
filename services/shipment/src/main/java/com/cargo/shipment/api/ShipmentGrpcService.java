package com.cargo.shipment.api;

import com.cargo.shipment.domain.ShipmentService;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.v1.CreateShipmentRequest;
import com.cargo.shipment.v1.CreateShipmentResponse;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC adapter for the Shipment Service. Translates proto messages to
 * domain calls, delegates to {@link ShipmentService}, and maps results
 * (or validation failures) back to gRPC responses.
 *
 * <p>Remaining RPCs fall through to the {@code ImplBase} default of
 * {@code UNIMPLEMENTED} until:
 * <ul>
 *   <li>T2.5 — {@code GetShipment}</li>
 *   <li>T2.6 — {@code ListShipments}</li>
 *   <li>T2.7 — {@code UpdateShipmentStatus}</li>
 *   <li>T2.8 — {@code CancelShipment}</li>
 * </ul>
 */
@GrpcService
public class ShipmentGrpcService extends ShipmentServiceGrpc.ShipmentServiceImplBase {

    private final ShipmentService service;

    public ShipmentGrpcService(ShipmentService service) {
        this.service = service;
    }

    @Override
    public void createShipment(
            CreateShipmentRequest request,
            StreamObserver<CreateShipmentResponse> responseObserver) {
        try {
            ShipmentEntity entity = service.createShipment(
                    ShipmentMapper.toDomain(request.getOrigin()),
                    ShipmentMapper.toDomain(request.getDestination()),
                    request.getCarrier(),
                    request.getWeightKg());
            CreateShipmentResponse response = CreateShipmentResponse.newBuilder()
                    .setShipment(ShipmentMapper.toProto(entity))
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription(e.getMessage())
                            .withCause(e)
                            .asRuntimeException());
        }
    }
}
