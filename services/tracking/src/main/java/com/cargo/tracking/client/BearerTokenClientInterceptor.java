package com.cargo.tracking.client;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Client interceptor that attaches {@code authorization: Bearer <jwt>}
 * to every outgoing call, with the token supplied per-call by a
 * {@link ClientCredentialsTokenProvider} so a cached-but-expired token
 * is refreshed transparently.
 */
public final class BearerTokenClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final ClientCredentialsTokenProvider tokenProvider;

    public BearerTokenClientInterceptor(ClientCredentialsTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(AUTHORIZATION, "Bearer " + tokenProvider.token());
                super.start(responseListener, headers);
            }
        };
    }
}
