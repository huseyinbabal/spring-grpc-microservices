package com.cargo.common.grpc.error;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * gRPC {@link ServerInterceptor} that translates domain exceptions
 * thrown from service handlers into the correct gRPC {@link Status}
 * codes before the call is closed to the client.
 *
 * <p>grpc-java wraps exceptions thrown from the server method in
 * {@code Status.UNKNOWN.withCause(t)} and calls {@code ServerCall.close}.
 * This advice wraps the {@link ServerCall} so it can inspect
 * {@link Status#getCause()} at close time and re-translate.
 *
 * <p>Mappings:
 * <ul>
 *   <li>{@link NotFoundException} → {@link Status#NOT_FOUND}</li>
 *   <li>{@link IllegalTransitionException} → {@link Status#FAILED_PRECONDITION}</li>
 * </ul>
 * Unrecognised causes are passed through untouched so handlers that
 * explicitly throw {@code StatusRuntimeException} keep control of
 * their status code.
 */
public final class GrpcExceptionAdvice implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall<ReqT, RespT> wrapped =
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        super.close(translate(status), trailers);
                    }
                };
        return next.startCall(wrapped, headers);
    }

    static Status translate(Status status) {
        Throwable cause = status.getCause();
        if (cause instanceof NotFoundException) {
            return Status.NOT_FOUND.withDescription(cause.getMessage()).withCause(cause);
        }
        if (cause instanceof IllegalTransitionException) {
            return Status.FAILED_PRECONDITION.withDescription(cause.getMessage()).withCause(cause);
        }
        return status;
    }
}
