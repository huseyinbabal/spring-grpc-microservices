package com.cargo.tracking.auth;

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
 * Mirrors the shipment service's GrpcAuthConfig — see that class for
 * the full rationale. Off by default; the compose stack enables it via
 * {@code CARGO_AUTH_ENABLED}.
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
