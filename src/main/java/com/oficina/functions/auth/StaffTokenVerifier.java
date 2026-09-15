package com.oficina.functions.auth;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.util.*;

/** Coarse role claims only: APP must re-read the staff account and current roles. */
public final class StaffTokenVerifier {
    private final Map<String, SecretKey> keys;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public StaffTokenVerifier(Map<String, SecretKey> keys, String issuer, String audience, Clock clock) {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("Pinned staff secrets required");
        Map<String, SecretKey> copy = new HashMap<>();
        keys.forEach((kid, key) -> {
            TokenVerification.configured(kid);
            if (key == null || key.getEncoded() == null || key.getEncoded().length < 32)
                throw new IllegalArgumentException("Staff secret must be at least 256 bits");
            copy.put(kid, new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
        });
        this.keys = Map.copyOf(copy);
        this.issuer = TokenVerification.configured(issuer);
        this.audience = TokenVerification.configured(audience);
        this.clock = Objects.requireNonNull(clock);
    }

    public VerifiedPrincipal verificar(String token) {
        var claims = TokenVerification.verify(token, "HS256", keys, issuer, audience, "staff", clock);
        if (claims.containsKey("scopes") || claims.containsKey("identity_version")) throw TokenVerification.invalid();
        return new VerifiedPrincipal("staff", claims.getSubject(),
                TokenVerification.permissions(claims.get("roles"), Set.of("ADMIN", "MECANICO")));
    }
}
