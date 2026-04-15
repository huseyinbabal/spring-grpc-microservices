package com.cargo.shipment.api;

import com.cargo.common.grpc.error.IllegalTransitionException;
import com.cargo.common.grpc.error.NotFoundException;
import com.cargo.common.v1.PageResult;
import com.cargo.shipment.domain.ShipmentService;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.v1.CreateShipmentRequest;
import com.cargo.shipment.v1.CreateShipmentResponse;
import com.cargo.shipment.v1.GetShipmentRequest;
import com.cargo.shipment.v1.GetShipmentResponse;
import com.cargo.shipment.v1.ListShipmentsRequest;
import com.cargo.shipment.v1.ListShipmentsResponse;
import com.cargo.shipment.v1.ShipmentServiceGrpc;
import com.cargo.shipment.v1.UpdateShipmentStatusRequest;
import com.cargo.shipment.v1.UpdateShipmentStatusResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.data.domain.Page;

import java.util.UUID;

/**
 * gRPC adapter for the Shipment Service. Translates proto messages to
 * domain calls, delegates to {@link ShipmentService}, and maps results
 * (or validation failures) back to gRPC responses.
 *
 * <p>Remaining RPCs fall through to the {@code ImplBase} default of
 * {@code UNIMPLEMENTED} until:
 * <ul>
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

    @Override
    public void getShipment(
            GetShipmentRequest request,
            StreamObserver<GetShipmentResponse> responseObserver) {
        try {
            ShipmentEntity entity = switch (request.getKeyCase()) {
                case ID -> service.findShipment(parseUuid(request.getId()));
                case TRACKING_CODE -> service.findShipmentByTrackingCode(request.getTrackingCode());
                case KEY_NOT_SET -> throw new IllegalArgumentException(
                        "GetShipmentRequest.key must be set (id or tracking_code)");
            };
            GetShipmentResponse response = GetShipmentResponse.newBuilder()
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
        } catch (NotFoundException e) {
            responseObserver.onError(
                    Status.NOT_FOUND
                            .withDescription(e.getMessage())
                            .withCause(e)
                            .asRuntimeException());
        }
    }

    @Override
    public void listShipments(
            ListShipmentsRequest request,
            StreamObserver<ListShipmentsResponse> responseObserver) {
        try {
            int pageNumber = parsePageToken(request.getPage().getToken());
            int pageSize = request.getPage().getSize();
            com.cargo.shipment.domain.ShipmentStatus statusFilter =
                    ShipmentMapper.toDomain(request.getStatusFilter());
            String carrierFilter = request.getCarrierFilter();

            Page<ShipmentEntity> page = service.listShipments(
                    statusFilter, carrierFilter, pageNumber, pageSize);

            ListShipmentsResponse.Builder responseBuilder = ListShipmentsResponse.newBuilder();
            for (ShipmentEntity entity : page.getContent()) {
                responseBuilder.addShipments(ShipmentMapper.toProto(entity));
            }
            String nextToken = page.hasNext() ? String.valueOf(pageNumber + 1) : "";
            responseBuilder.setPage(PageResult.newBuilder().setNextToken(nextToken).build());
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT
                            .withDescription(e.getMessage())
                            .withCause(e)
                            .asRuntimeException());
        }
    }

    @Override
    public void updateShipmentStatus(
            UpdateShipmentStatusRequest request,
            StreamObserver<UpdateShipmentStatusResponse> responseObserver) {
        try {
            UUID id = parseUuid(request.getId());
            com.cargo.shipment.domain.ShipmentStatus newStatus =
                    ShipmentMapper.toDomain(request.getNewStatus());
            if (newStatus == null) {
                throw new IllegalArgumentException(
                        "new_status is required and must be a recognized value");
            }
            ShipmentEntity entity = service.updateShipmentStatus(id, newStatus);
            UpdateShipmentStatusResponse response = UpdateShipmentStatusResponse.newBuilder()
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
        } catch (NotFoundException e) {
            responseObserver.onError(
                    Status.NOT_FOUND
                            .withDescription(e.getMessage())
                            .withCause(e)
                            .asRuntimeException());
        } catch (IllegalTransitionException e) {
            responseObserver.onError(
                    Status.FAILED_PRECONDITION
                            .withDescription(e.getMessage())
                            .withCause(e)
                            .asRuntimeException());
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("id must be a valid UUID, got: " + value);
        }
    }

    private static int parsePageToken(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            int n = Integer.parseInt(token);
            if (n < 0) {
                throw new IllegalArgumentException("page token must be non-negative, got " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("page token must be an integer, got: " + token);
        }
    }
}
