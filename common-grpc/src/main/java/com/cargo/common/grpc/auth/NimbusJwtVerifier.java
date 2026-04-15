package com.cargo.common.grpc.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link JwtVerifier} implementation backed by nimbus-jose-jwt. Supports
 * any RS256-signed token whose signing key is resolvable from the supplied
 * {@link JWKSource} (typically a Keycloak JWKS endpoint, an in-memory set
 * for tests, or a cached remote set in production).
 *
 * <p>Validates signature, expiration, and not-before, then extracts the
 * Keycloak-style claims into a {@link JwtPrincipal}: {@code sub},
 * {@code preferred_username}, and {@code realm_access.roles}.
 */
public final class NimbusJwtVerifier implements JwtVerifier {

    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public NimbusJwtVerifier(JWKSource<SecurityContext> keySource) {
        ConfigurableJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        JWSKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
        p.setJWSKeySelector(keySelector);
        this.processor = p;
    }

    @Override
    public JwtPrincipal verify(String jwt) throws JwtVerificationException {
        try {
            JWTClaimsSet claims = processor.process(jwt, null);
            return toPrincipal(claims);
        } catch (Exception e) {
            throw new JwtVerificationException("jwt verification failed: " + e.getMessage(), e);
        }
    }

    private static JwtPrincipal toPrincipal(JWTClaimsSet claims) {
        String subject = claims.getSubject();
        Object usernameClaim = claims.getClaim("preferred_username");
        String username = usernameClaim instanceof String s ? s : null;
        return new JwtPrincipal(subject, username, extractRoles(claims));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> extractRoles(JWTClaimsSet claims) {
        Object realmAccess = claims.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
            return Set.copyOf((List<String>) roles);
        }
        return Set.of();
    }
}
