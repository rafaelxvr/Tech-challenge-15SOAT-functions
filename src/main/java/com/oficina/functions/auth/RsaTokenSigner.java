package com.oficina.functions.auth;

import io.jsonwebtoken.Jwts;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Only the CPF verification runtime receives this private key. */
public final class RsaTokenSigner implements TokenSigner {
    private final PrivateKey key;
    private final String kid;
    private final String issuer;
    private final String audience;

    public RsaTokenSigner(PrivateKey key, String kid, String issuer, String audience) {
        if (!(key instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048)
            throw new IllegalArgumentException("RSA signing key must be at least 2048 bits");
        this.key = key;
        this.kid = TokenVerification.configured(kid);
        this.issuer = TokenVerification.configured(issuer);
        this.audience = TokenVerification.configured(audience);
    }

    @Override public String emitir(ClienteSnapshot cliente, Instant agora) {
        Objects.requireNonNull(cliente);
        Objects.requireNonNull(agora);
        if (cliente.id() == null || !cliente.ativo() || cliente.versaoIdentidade() < 1)
            throw new IllegalArgumentException("Active customer identity required");
        try {
            return Jwts.builder().header().keyId(kid).and()
                    .issuer(issuer).audience().add(audience).and().subject(cliente.id().toString())
                    .claim("principal_type", "customer").claim("token_use", "access")
                    .claim("identity_version", cliente.versaoIdentidade())
                    .claim("scopes", List.of("orders:read:self", "orders:decide:self"))
                    .issuedAt(Date.from(agora)).expiration(Date.from(agora.plusSeconds(900)))
                    .signWith(key, Jwts.SIG.RS256).compact();
        } catch (RuntimeException exception) {
            throw AutenticacaoException.indisponivel();
        }
    }
}
