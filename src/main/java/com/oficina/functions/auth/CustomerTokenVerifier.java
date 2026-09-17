package com.oficina.functions.auth;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.*;

public final class CustomerTokenVerifier {
    private final Map<String, PublicKey> keys;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public CustomerTokenVerifier(Map<String, PublicKey> keys, String issuer, String audience, Clock clock) {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("Pinned RSA public keys required");
        keys.forEach((kid, key) -> {
            TokenVerification.configured(kid);
            if (!(key instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048)
                throw new IllegalArgumentException("RSA public key must be at least 2048 bits");
        });
        this.keys = Map.copyOf(keys);
        this.issuer = TokenVerification.configured(issuer);
        this.audience = TokenVerification.configured(audience);
        this.clock = Objects.requireNonNull(clock);
    }

    public VerifiedPrincipal verificar(String token) {
        var claims = TokenVerification.verify(token, "RS256", keys, issuer, audience, "customer", clock);
        try {
            String subject = claims.getSubject();
            if (!UUID.fromString(subject).toString().equals(subject)) throw TokenVerification.invalid();
            Object version = claims.get("identity_version");
            if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() < 1
                    || claims.containsKey("roles")
                    || claims.getExpiration().toInstant().getEpochSecond() - claims.getIssuedAt().toInstant().getEpochSecond() != 900)
                throw TokenVerification.invalid();
            return new VerifiedPrincipal("customer", subject, TokenVerification.permissions(claims.get("scopes"),
                    Set.of("orders:read:self", "orders:decide:self")));
        } catch (RuntimeException exception) {
            throw TokenVerification.invalid();
        }
    }
}
