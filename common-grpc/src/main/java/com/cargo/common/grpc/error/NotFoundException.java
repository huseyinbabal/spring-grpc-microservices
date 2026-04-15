package com.cargo.common.grpc.error;

/**
 * Thrown when a requested aggregate or resource does not exist.
 * {@link GrpcExceptionAdvice} maps this to {@code Status.NOT_FOUND}.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
