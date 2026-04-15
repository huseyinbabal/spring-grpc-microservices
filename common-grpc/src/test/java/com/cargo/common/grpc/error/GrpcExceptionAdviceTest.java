package com.cargo.common.grpc.error;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcExceptionAdviceTest {

    private final GrpcExceptionAdvice advice = new GrpcExceptionAdvice();

    @Test
    void translate_null_cause_returns_original_status() {
        Status original = Status.UNKNOWN.withDescription("no cause");

        Status translated = GrpcExceptionAdvice.translate(original);

        assertThat(translated).isSameAs(original);
    }

    @Test
    void translate_not_found_exception_returns_not_found_status() {
        NotFoundException cause = new NotFoundException("shipment ship-1 does not exist");
        Status original = Status.UNKNOWN.withCause(cause);

        Status translated = GrpcExceptionAdvice.translate(original);

        assertThat(translated.getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(translated.getDescription()).isEqualTo("shipment ship-1 does not exist");
        assertThat(translated.getCause()).isSameAs(cause);
    }

    @Test
    void translate_illegal_transition_returns_failed_precondition() {
        IllegalTransitionException cause =
                new IllegalTransitionException("CREATED → DELIVERED is not allowed");
        Status original = Status.UNKNOWN.withCause(cause);

        Status translated = GrpcExceptionAdvice.translate(original);

        assertThat(translated.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(translated.getDescription()).isEqualTo("CREATED → DELIVERED is not allowed");
        assertThat(translated.getCause()).isSameAs(cause);
    }

    @Test
    void translate_unknown_cause_passes_through_unchanged() {
        RuntimeException cause = new RuntimeException("boom");
        Status original = Status.UNKNOWN.withCause(cause);

        Status translated = GrpcExceptionAdvice.translate(original);

        assertThat(translated).isSameAs(original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interceptor_wraps_server_call_so_close_is_translated() {
        ServerCall<Object, Object> underlying = mock(ServerCall.class);
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        ServerCall.Listener<Object> delegatedListener = mock(ServerCall.Listener.class);

        AtomicReference<ServerCall<Object, Object>> captured = new AtomicReference<>();
        when(next.startCall(any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return delegatedListener;
        });

        ServerCall.Listener<Object> result = advice.interceptCall(underlying, new Metadata(), next);

        assertThat(result).isSameAs(delegatedListener);
        assertThat(captured.get())
                .as("interceptor must pass a wrapped ServerCall to the downstream handler")
                .isNotSameAs(underlying);

        captured.get().close(
                Status.UNKNOWN.withCause(new NotFoundException("missing")),
                new Metadata());

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(underlying).close(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(status.getValue().getDescription()).isEqualTo("missing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void interceptor_leaves_successful_close_alone() {
        ServerCall<Object, Object> underlying = mock(ServerCall.class);
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        ServerCall.Listener<Object> delegatedListener = mock(ServerCall.Listener.class);

        AtomicReference<ServerCall<Object, Object>> captured = new AtomicReference<>();
        when(next.startCall(any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return delegatedListener;
        });

        advice.interceptCall(underlying, new Metadata(), next);
        captured.get().close(Status.OK, new Metadata());

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(underlying).close(status.capture(), any());
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.OK);
    }
}
