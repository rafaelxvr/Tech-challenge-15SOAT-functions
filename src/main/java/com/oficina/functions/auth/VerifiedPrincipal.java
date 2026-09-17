package com.oficina.functions.auth;

import java.util.Objects;
import java.util.Set;

/** Gateway claims only. APP independently rechecks current identity, roles and ownership. */
public record VerifiedPrincipal(String principalType, String subject, Set<String> permissions) {
    public VerifiedPrincipal {
        Objects.requireNonNull(principalType);
        Objects.requireNonNull(subject);
        permissions = Set.copyOf(permissions);
    }
    @Override public String toString() { return "VerifiedPrincipal[redacted]"; }
}
