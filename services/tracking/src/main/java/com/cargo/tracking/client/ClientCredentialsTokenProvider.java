package com.cargo.tracking.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Fetches and caches an OAuth2 client-credentials access token from
 * Keycloak for the Tracking → Shipment machine-to-machine channel.
 *
 * <p>The Shipment service requires a JWT on every call (its
 * {@code JwtAuthInterceptor}), so the internal sync-enrichment client
 * must authenticate as a service. It uses the {@code cargo-api} client's
 * service account (client_credentials grant) — the JWT then identifies
 * <em>which service</em> is calling, while mTLS already proved the peer
 * at the transport layer.
 *
 * <p>The token is cached until 30s before expiry; refresh is a single
 * blocking HTTP POST guarded by {@code synchronized}, which is fine at
 * this call volume. A refresh failure throws — the caller's circuit
 * breaker treats it like any other downstream fault and degrades to the
 * enrichment fallback.
 */
public class ClientCredentialsTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsTokenProvider.class);
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(
            String tokenUri, String clientId, String clientSecret, ObjectMapper objectMapper) {
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = objectMapper;
    }

    /** Returns a currently-valid access token, refreshing if needed. */
    public String token() {
        if (Instant.now().isAfter(expiresAt.minus(EXPIRY_SKEW))) {
            refresh();
        }
        return cachedToken;
    }

    private synchronized void refresh() {
        if (Instant.now().isBefore(expiresAt.minus(EXPIRY_SKEW))) {
            return; // another thread already refreshed
        }
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "token endpoint returned HTTP " + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            cachedToken = body.path("access_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(60);
            if (cachedToken == null) {
                throw new IllegalStateException("token response had no access_token");
            }
            expiresAt = Instant.now().plusSeconds(expiresIn);
            log.debug("refreshed service token, valid for {}s", expiresIn);
        } catch (IOException e) {
            throw new IllegalStateException("token refresh failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("token refresh interrupted", e);
        }
    }
}
