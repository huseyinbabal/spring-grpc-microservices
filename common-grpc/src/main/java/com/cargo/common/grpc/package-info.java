/**
 * Shared gRPC interceptors, exception advice, and mTLS client helpers
 * for the cargo tracking platform's three services.
 *
 * <p>Real content lands in follow-up tasks:
 * <ul>
 *   <li>T1.2 — {@code JwtAuthInterceptor}</li>
 *   <li>T1.3 — {@code GrpcExceptionAdvice}</li>
 *   <li>T1.4 — {@code MtlsClientConfig}</li>
 * </ul>
 */
package com.cargo.common.grpc;
