package com.cargo.common.grpc.auth;

/**
 * Verifies a JWT and produces a {@link JwtPrincipal} on success.
 * Implementations must validate signature, expiration, and any other
 * integrity claims before returning.
 */
public interface JwtVerifier {
    JwtPrincipal verify(String jwt) throws JwtVerificationException;
}
