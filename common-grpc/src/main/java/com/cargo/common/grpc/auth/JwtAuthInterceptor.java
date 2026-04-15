package com.cargo.common.grpc.auth;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * gRPC {@link ServerInterceptor} that authenticates every call by
 * validating a JWT in the {@code authorization} header. On success it
 * stores the {@link JwtPrincipal} in the gRPC {@link Context}; on failure
 * it closes the call with {@link Status#UNAUTHENTICATED}.
 */
public final class JwtAuthInterceptor implements ServerInterceptor {

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER_PREFIX = "Bearer ";

    public static final Context.Key<JwtPrincipal> PRINCIPAL = Context.key("jwt-principal");

    private final JwtVerifier verifier;

    public JwtAuthInterceptor(JwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String header = headers.get(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return reject(call, "missing or malformed authorization header");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return reject(call, "empty bearer token");
        }

        JwtPrincipal principal;
        try {
            principal = verifier.verify(token);
        } catch (JwtVerificationException e) {
            return reject(call, e.getMessage());
        }

        Context ctx = Context.current().withValue(PRINCIPAL, principal);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(
            ServerCall<ReqT, RespT> call, String description) {
        call.close(Status.UNAUTHENTICATED.withDescription(description), new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
