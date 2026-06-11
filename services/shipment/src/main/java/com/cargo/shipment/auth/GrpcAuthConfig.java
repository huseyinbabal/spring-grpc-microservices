package com.cargo.shipment.auth;

import com.cargo.common.grpc.auth.JwtAuthInterceptor;
import com.cargo.common.grpc.auth.NimbusJwtVerifier;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Requires a valid Keycloak-issued JWT on every gRPC call (T8.3).
 *
 * <p>Registers {@link JwtAuthInterceptor} as a global server interceptor:
 * a call without a verifiable {@code authorization: Bearer <jwt>} header
 * is closed with {@code UNAUTHENTICATED} before it reaches any handler —
 * including health and reflection, matching the "no anonymous RPCs"
 * policy.
 *
 * <p>Signing keys are resolved from the Keycloak JWKS endpoint and
 * cached by nimbus, so steady-state verification is a local signature
 * check. Off by default ({@code cargo.auth.enabled=false}) so bare
 * {@code mvn spring-boot:run} and the in-process test profile stay
 * tokenless; the compose stack enables it via {@code CARGO_AUTH_ENABLED}.
 */
@Configuration
@ConditionalOnProperty(name = "cargo.auth.enabled", havingValue = "true")
public class GrpcAuthConfig {

    @GrpcGlobalServerInterceptor
    public JwtAuthInterceptor jwtAuthInterceptor(
            @Value("${cargo.auth.jwks-uri}") String jwksUri) throws MalformedURLException {
        JWKSource<SecurityContext> keySource = JWKSourceBuilder
                .create(new URL(jwksUri))
                .cache(true)
                .build();
        return new JwtAuthInterceptor(new NimbusJwtVerifier(keySource));
    }
}
