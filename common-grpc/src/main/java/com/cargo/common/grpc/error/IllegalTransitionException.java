package com.cargo.common.grpc.error;

/**
 * Thrown when an aggregate is asked to make an illegal state-machine
 * transition (e.g. cancelling an already-delivered shipment).
 * {@link GrpcExceptionAdvice} maps this to
 * {@code Status.FAILED_PRECONDITION}.
 */
public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(String message) {
        super(message);
    }

    public IllegalTransitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
