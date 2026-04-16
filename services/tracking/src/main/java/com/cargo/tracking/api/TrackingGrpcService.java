package com.cargo.tracking.api;

import com.cargo.tracking.v1.GetTrackingRequest;
import com.cargo.tracking.v1.GetTrackingResponse;
import com.cargo.tracking.v1.ReportLocationRequest;
import com.cargo.tracking.v1.ReportLocationResponse;
import com.cargo.tracking.v1.StreamTrackingRequest;
import com.cargo.tracking.v1.StreamTrackingResponse;
import com.cargo.tracking.v1.TrackingServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * Scaffold for the Tracking gRPC adapter. All three RPCs throw
 * UNIMPLEMENTED until their dedicated tasks (T4.4 ReportLocation,
 * T4.5 GetTracking, T5.2 StreamTracking) wire real behavior.
 */
@GrpcService
public class TrackingGrpcService extends TrackingServiceGrpc.TrackingServiceImplBase {

    @Override
    public void reportLocation(
            ReportLocationRequest request,
            StreamObserver<ReportLocationResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("ReportLocation lands in T4.4")
                .asRuntimeException());
    }

    @Override
    public void getTracking(
            GetTrackingRequest request,
            StreamObserver<GetTrackingResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("GetTracking lands in T4.5")
                .asRuntimeException());
    }

    @Override
    public void streamTracking(
            StreamTrackingRequest request,
            StreamObserver<StreamTrackingResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("StreamTracking lands in T5.2")
                .asRuntimeException());
    }
}
