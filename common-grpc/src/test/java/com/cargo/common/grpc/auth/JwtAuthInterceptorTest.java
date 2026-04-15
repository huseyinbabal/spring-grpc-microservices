package com.cargo.common.grpc.auth;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthInterceptorTest {

    @SuppressWarnings("unchecked")
    private final ServerCall<Object, Object> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    private final ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
    @SuppressWarnings("unchecked")
    private final ServerCall.Listener<Object> delegatedListener = mock(ServerCall.Listener.class);
    private final JwtVerifier verifier = mock(JwtVerifier.class);
    private final JwtAuthInterceptor interceptor = new JwtAuthInterceptor(verifier);

    @BeforeEach
    void setup() {
        when(next.startCall(any(), any())).thenReturn(delegatedListener);
    }

    @Test
    void valid_jwt_puts_principal_on_context_and_invokes_next() throws Exception {
        JwtPrincipal principal = new JwtPrincipal("sub-1", "alice", Set.of("ops"));
        when(verifier.verify("signed-token")).thenReturn(principal);

        AtomicReference<JwtPrincipal> captured = new AtomicReference<>();
        when(next.startCall(any(), any())).thenAnswer(inv -> {
            captured.set(JwtAuthInterceptor.PRINCIPAL.get());
            return delegatedListener;
        });

        Metadata headers = new Metadata();
        headers.put(JwtAuthInterceptor.AUTHORIZATION, "Bearer signed-token");

        ServerCall.Listener<Object> result = interceptor.interceptCall(call, headers, next);

        assertThat(result).isNotNull();
        assertThat(captured.get())
                .as("principal stored on gRPC Context during next.startCall")
                .isEqualTo(principal);
        verify(call, never()).close(any(), any());
    }

    @Test
    void missing_authorization_header_closes_unauthenticated() {
        Metadata headers = new Metadata();

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void non_bearer_scheme_closes_unauthenticated() {
        Metadata headers = new Metadata();
        headers.put(JwtAuthInterceptor.AUTHORIZATION, "Basic dXNlcjpwYXNz");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void empty_bearer_token_closes_unauthenticated() {
        Metadata headers = new Metadata();
        headers.put(JwtAuthInterceptor.AUTHORIZATION, "Bearer ");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void failed_verification_closes_unauthenticated() throws Exception {
        when(verifier.verify("bad-token")).thenThrow(new JwtVerificationException("bad signature"));

        Metadata headers = new Metadata();
        headers.put(JwtAuthInterceptor.AUTHORIZATION, "Bearer bad-token");

        interceptor.interceptCall(call, headers, next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }
}
