package com.cargo.common.grpc.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NimbusJwtVerifierTest {

    private static RSAKey rsaKey;
    private static NimbusJwtVerifier verifier;

    @BeforeAll
    static void setup() throws Exception {
        rsaKey = new RSAKeyGenerator(2048)
                .keyID("test-key")
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        JWKSource<SecurityContext> keySource =
                new ImmutableJWKSet<>(new JWKSet(rsaKey.toPublicJWK()));
        verifier = new NimbusJwtVerifier(keySource);
    }

    @Test
    void valid_token_returns_principal_with_claims() throws Exception {
        String token = sign(rsaKey, claims(Instant.now().plusSeconds(300))
                .subject("user-123")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER", "COURIER")))
                .build());

        JwtPrincipal principal = verifier.verify(token);

        assertThat(principal.subject()).isEqualTo("user-123");
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.roles()).containsExactlyInAnyOrder("CUSTOMER", "COURIER");
    }

    @Test
    void token_without_realm_access_returns_empty_roles() throws Exception {
        String token = sign(rsaKey, claims(Instant.now().plusSeconds(300))
                .subject("user-123")
                .claim("preferred_username", "bob")
                .build());

        JwtPrincipal principal = verifier.verify(token);

        assertThat(principal.roles()).isEmpty();
    }

    @Test
    void token_signed_by_unknown_key_throws() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048)
                .keyID("other")
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        String token = sign(other, claims(Instant.now().plusSeconds(300))
                .subject("u")
                .build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void expired_token_throws() throws Exception {
        String token = sign(rsaKey, claims(Instant.now().minusSeconds(60))
                .subject("u")
                .build());

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtVerificationException.class);
    }

    @Test
    void malformed_token_throws() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOf(JwtVerificationException.class);
    }

    private static JWTClaimsSet.Builder claims(Instant expiry) {
        return new JWTClaimsSet.Builder()
                .issueTime(new Date())
                .expirationTime(Date.from(expiry));
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
