package com.cargo.common.grpc.auth;

import java.util.Set;

/**
 * Authenticated caller identity extracted from a verified JWT.
 */
public record JwtPrincipal(String subject, String username, Set<String> roles) {
    public JwtPrincipal {
        roles = Set.copyOf(roles);
    }
}
