package com.cargo.common.grpc.auth;

public class JwtVerificationException extends Exception {
    public JwtVerificationException(String message) {
        super(message);
    }

    public JwtVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
