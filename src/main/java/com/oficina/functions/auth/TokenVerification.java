package com.oficina.functions.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import java.security.Key;
import java.time.Clock;
import java.util.*;

/** Pinned, local key resolution; no token-controlled network or filesystem lookups. */
final class TokenVerification {
    private TokenVerification() {}

    static String configured(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("JWT trust configuration required");
        return value;
    }

    static AutenticacaoException invalid() { return new AutenticacaoException(401, "CREDENCIAIS_INVALIDAS"); }

    static Claims verify(String token, String algorithm, Map<String, ? extends Key> keys,
                         String issuer, String audience, String type, Clock clock) {
        try {
            if (token == null || token.length() > 16384) throw invalid();
            Claims claims = Jwts.parser().clock(() -> Date.from(clock.instant()))
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override public Key locate(ProtectedHeader header) {
                            if (!algorithm.equals(header.getAlgorithm()) || header.getKeyId() == null
                                    || Set.of("jku", "x5u", "jwk", "x5c", "crit", "zip", "b64").stream().anyMatch(header::containsKey))
                                throw invalid();
                            Key key = keys.get(header.getKeyId());
                            if (key == null) throw invalid();
                            return key;
                        }
                    }).requireIssuer(issuer).requireAudience(audience)
                    .require("principal_type", type).require("token_use", "access")
                    .build().parseSignedClaims(token).getPayload();
            Date now = Date.from(clock.instant());
            if (claims.getIssuedAt() == null || claims.getExpiration() == null
                    || claims.getIssuedAt().after(now) || !claims.getExpiration().after(now)
                    || !claims.getExpiration().after(claims.getIssuedAt())
                    || !Set.of(audience).equals(claims.getAudience())
                    || claims.getSubject() == null || claims.getSubject().isBlank()) throw invalid();
            return claims;
        } catch (RuntimeException exception) {
            // JWT parser messages can expose claims: never attach their cause or message.
            throw invalid();
        }
    }

    static Set<String> permissions(Object raw, Set<String> allowed) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) throw invalid();
        Set<String> result = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || !allowed.contains(text)) throw invalid();
            result.add(text);
        }
        return Set.copyOf(result);
    }
}
