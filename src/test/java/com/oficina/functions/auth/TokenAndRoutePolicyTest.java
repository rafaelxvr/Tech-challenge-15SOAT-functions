package com.oficina.functions.auth;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TokenAndRoutePolicyTest {
    static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000301");
    static final String KID = "customer-test-key";
    static final KeyPair RSA = Jwts.SIG.RS256.keyPair().build();
    static final SecretKey HMAC = Jwts.SIG.HS256.key().build();
    static final ObjectMapper JSON = new ObjectMapper();

    CustomerTokenVerifier customer() { return new CustomerTokenVerifier(Map.of(KID, RSA.getPublic()), "oficina-staging-customer", "oficina-staging-api", CLOCK); }
    StaffTokenVerifier staff() { return new StaffTokenVerifier(Map.of("staff-test-key", HMAC), "oficina-staging-staff", "oficina-staging-api", CLOCK); }
    Map<String, Object> claims(boolean customer) throws Exception {
        return JSON.convertValue(JSON.readTree(Path.of("contracts/phase3-v2/token-claims.json").toFile())
                .path("environments").path("staging").path(customer ? "customerAccess" : "staffAccess"), new TypeReference<>() {});
    }
    Map<String, Object> header(boolean customer) { return new HashMap<>(Map.of("kid", customer ? KID : "staff-test-key", "alg", customer ? "RS256" : "HS256")); }

    // Independent JOSE signer permits malformed headers/claims for negative trust vectors.
    String raw(Map<String, Object> claims, Map<String, Object> header, boolean rsa) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String unsigned = encoder.encodeToString(JSON.writeValueAsBytes(header)) + "." + encoder.encodeToString(JSON.writeValueAsBytes(claims));
        byte[] bytes = unsigned.getBytes(StandardCharsets.US_ASCII);
        byte[] signature;
        if (rsa) {
            var signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(RSA.getPrivate()); signer.update(bytes); signature = signer.sign();
        } else {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(HMAC); signature = mac.doFinal(bytes);
        }
        return unsigned + "." + encoder.encodeToString(signature);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "issuerMissing", "audience", "audienceMissing", "extraAudience", "expired", "expirationMissing",
            "issuedAtMissing", "futureIssuedAt", "futureNotBefore", "purpose", "purposeMissing", "principal", "principalMissing", "subject",
            "kidMissing", "kidUnknown", "jku", "x5u", "jwk", "x5c", "tampered", "environment", "algorithm", "crit", "zip", "b64"})
    void rejectsUntrustedTokensForBothActors(String attack) throws Exception {
        for (boolean isCustomer : List.of(true, false)) {
            var c = claims(isCustomer); var h = header(isCustomer);
            switch (attack) {
                case "issuer" -> c.put("iss", "untrusted");
                case "issuerMissing" -> c.remove("iss");
                case "audience" -> c.put("aud", "other");
                case "audienceMissing" -> c.remove("aud");
                case "extraAudience" -> c.put("aud", List.of("oficina-staging-api", "other"));
                case "expired" -> c.put("exp", NOW.getEpochSecond());
                case "expirationMissing" -> c.remove("exp");
                case "issuedAtMissing" -> c.remove("iat");
                case "futureIssuedAt" -> c.put("iat", NOW.plusSeconds(1).getEpochSecond());
                case "futureNotBefore" -> c.put("nbf", NOW.plusSeconds(1).getEpochSecond());
                case "purpose" -> c.put("token_use", "refresh");
                case "purposeMissing" -> c.remove("token_use");
                case "principal" -> c.put("principal_type", isCustomer ? "staff" : "customer");
                case "principalMissing" -> c.remove("principal_type");
                case "subject" -> c.remove("sub");
                case "kidMissing" -> h.remove("kid");
                case "kidUnknown" -> h.put("kid", "unknown");
                case "jku", "x5u" -> h.put(attack, "https://untrusted.invalid/keys");
                case "jwk" -> h.put("jwk", Map.of("kty", "oct", "k", "YWJj"));
                case "x5c" -> h.put("x5c", List.of("YWJj"));
                case "environment" -> c.put("iss", isCustomer ? "oficina-production-customer" : "oficina-production-staff");
                case "algorithm" -> h.put("alg", isCustomer ? "HS256" : "RS256");
                case "crit" -> h.put("crit", List.of("unknown"));
                case "zip" -> h.put("zip", "DEF");
                case "b64" -> h.put("b64", false);
            }
            String signed = raw(c, h, isCustomer);
            if (attack.equals("tampered")) { String[] p = signed.split("\\."); p[2] = (p[2].startsWith("A") ? "B" : "A") + p[2].substring(1); signed = String.join(".", p); }
            String token = signed;
            assertThatThrownBy(() -> { if (isCustomer) customer().verificar(token); else staff().verificar(token); })
                    .as("%s customer=%s", attack, isCustomer).isInstanceOf(AutenticacaoException.class)
                    .hasMessage("CREDENCIAIS_INVALIDAS").hasNoCause();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missingVersion", "stringVersion", "fractionVersion", "zeroVersion", "negativeVersion", "subject", "shortUuid", "roles", "unknownScope", "missingScopes", "emptyScopes", "badScopeType", "lifetime"})
    void rejectsCustomerClaimConfusion(String attack) throws Exception {
        var c = claims(true);
        switch (attack) {
            case "missingVersion" -> c.remove("identity_version");
            case "stringVersion" -> c.put("identity_version", "1");
            case "fractionVersion" -> c.put("identity_version", 1.5);
            case "zeroVersion" -> c.put("identity_version", 0);
            case "negativeVersion" -> c.put("identity_version", -1);
            case "subject" -> c.put("sub", "staff@example.invalid");
            case "shortUuid" -> c.put("sub", "0-0-0-0-1");
            case "roles" -> c.put("roles", List.of("ADMIN"));
            case "unknownScope" -> c.put("scopes", List.of("admin:all"));
            case "missingScopes" -> c.remove("scopes");
            case "emptyScopes" -> c.put("scopes", List.of());
            case "badScopeType" -> c.put("scopes", List.of(1));
            case "lifetime" -> c.put("exp", NOW.plusSeconds(901).getEpochSecond());
        }
        String token = raw(c, header(true), true);
        assertThatThrownBy(() -> customer().verificar(token)).isInstanceOf(AutenticacaoException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"scopes", "identity_version", "missingRoles", "wrongRole", "emptyRoles", "rolesType"})
    void rejectsStaffClaimConfusion(String attack) throws Exception {
        var c = claims(false);
        switch (attack) {
            case "scopes" -> c.put("scopes", List.of("orders:read:self"));
            case "identity_version" -> c.put("identity_version", 1);
            case "missingRoles" -> c.remove("roles");
            case "wrongRole" -> c.put("roles", List.of("CLIENTE"));
            case "emptyRoles" -> c.put("roles", List.of());
            case "rolesType" -> c.put("roles", "ADMIN");
        }
        String token = raw(c, header(false), false);
        assertThatThrownBy(() -> staff().verificar(token)).isInstanceOf(AutenticacaoException.class);
    }

    @Test void authorizerDistinguishesInvalidCredentialsFromDeniedRoutes() throws Exception {
        var authorizer = new Authorizer(customer(), staff(), new RoutePolicy());
        for (String bearer : Arrays.asList(null, "", "Basic abc", "Bearer invalid", "Bearer a.b.c", "Bearer a.b.c "))
            assertThatThrownBy(() -> authorizer.autorizar(bearer, "GET /api/clientes")).isInstanceOfSatisfying(AutenticacaoException.class, e -> assertThat(e.status()).isEqualTo(401));
        String customer = raw(claims(true), header(true), true);
        String staff = raw(claims(false), header(false), false);
        assertThat(authorizer.autorizar("Bearer " + customer, "POST /api/ordens-servico")).isFalse();
        assertThat(authorizer.autorizar("bearer " + customer, "GET /api/ordens-servico/{numero}/acompanhamento")).isTrue();
        assertThat(authorizer.autorizar("Bearer " + staff, "GET /api/admin/relatorios/ordens")).isTrue();
        assertThat(authorizer.autorizar("Bearer " + staff, "$default")).isFalse();
        assertThat(authorizer.autorizar("Bearer " + staff, "POST /api/auth/login")).isFalse();
        assertThatThrownBy(() -> customer().verificar(staff)).isInstanceOf(AutenticacaoException.class);
        assertThatThrownBy(() -> staff().verificar(customer)).isInstanceOf(AutenticacaoException.class);
    }

    @Test void productionTrustAndKeyFamiliesRemainSeparated() throws Exception {
        String customer = raw(claims(true), header(true), true);
        String staff = raw(claims(false), header(false), false);
        var production = new CustomerTokenVerifier(Map.of(KID, RSA.getPublic()), "oficina-production-customer", "oficina-production-api", CLOCK);
        var productionStaff = new StaffTokenVerifier(Map.of("staff-test-key", HMAC), "oficina-production-staff", "oficina-production-api", CLOCK);
        assertThatThrownBy(() -> production.verificar(customer)).isInstanceOf(AutenticacaoException.class);
        assertThatThrownBy(() -> productionStaff.verificar(staff)).isInstanceOf(AutenticacaoException.class);
        var other = new CustomerTokenVerifier(Map.of(KID, Jwts.SIG.RS256.keyPair().build().getPublic()), "oficina-staging-customer", "oficina-staging-api", CLOCK);
        assertThatThrownBy(() -> other.verificar(customer)).isInstanceOf(AutenticacaoException.class);
        var publicAsHmac = new SecretKeySpec(RSA.getPublic().getEncoded(), "HmacSHA256");
        String confusion = Jwts.builder().header().keyId(KID).and().claims(claims(true)).signWith(publicAsHmac, Jwts.SIG.HS256).compact();
        assertThatThrownBy(() -> customer().verificar(confusion)).isInstanceOf(AutenticacaoException.class);
    }

    @Test void publicKeyRotationOverlapsOldAndNewKeys() throws Exception {
        KeyPair next = Jwts.SIG.RS256.keyPair().build();
        var overlap = new CustomerTokenVerifier(Map.of(KID, RSA.getPublic(), "next", next.getPublic()), "oficina-staging-customer", "oficina-staging-api", CLOCK);
        String oldToken = raw(claims(true), header(true), true);
        String nextToken = new RsaTokenSigner(next.getPrivate(), "next", "oficina-staging-customer", "oficina-staging-api")
                .emitir(new ClienteSnapshot(ID, "", true, "", 1), NOW);
        assertThat(overlap.verificar(oldToken)).isEqualTo(overlap.verificar(nextToken));
        var retired = new CustomerTokenVerifier(Map.of("next", next.getPublic()), "oficina-staging-customer", "oficina-staging-api", CLOCK);
        assertThatThrownBy(() -> retired.verificar(oldToken)).isInstanceOf(AutenticacaoException.class);
    }

    @Test void everyV2GrantUsesExactActorRolesAndScopes() throws Exception {
        var policy = new RoutePolicy();
        var matrix = JSON.readTree(Path.of("contracts/phase3-v2/routes.json").toFile());
        for (var route : matrix.path("routes")) {
            String key = route.path("method").asText() + " " + route.path("path").asText();
            for (var actor : List.of("customer", "staff", "anonymous", "unknown")) {
                for (Set<String> permissions : List.of(Set.<String>of(), Set.of("ADMIN"), Set.of("MECANICO"), Set.of("orders:read:self"), Set.of("orders:decide:self"))) {
                    boolean expected = false;
                    for (var grant : route.path("grants")) {
                        Set<String> roles = JSON.convertValue(grant.path("roles"), new TypeReference<>() {});
                        Set<String> scopes = JSON.convertValue(grant.path("scopes"), new TypeReference<>() {});
                        expected |= route.path("decision").asText().equals("ALLOW") && grant.path("actor").asText().equals(actor)
                                && permissions.containsAll(scopes) && (roles.isEmpty() || roles.stream().anyMatch(permissions::contains));
                    }
                    assertThat(policy.permite(new VerifiedPrincipal(actor, ID.toString(), permissions), key)).as("%s %s %s", key, actor, permissions).isEqualTo(expected);
                }
            }
        }
        var admin = new VerifiedPrincipal("staff", "staff@example.invalid", Set.of("ADMIN"));
        for (String key : Arrays.asList(null, "$default", "GET /api/unknown", "GET /api/clientes/123", "GET /api/clientes/", "get /api/clientes", "POST /api/ordens-servico/email/atualizar-status"))
            assertThat(policy.permite(admin, key)).isFalse();
        assertThat(policy.permite(null, "GET /api/clientes")).isFalse();
    }

    @Test void exportsPublicOnlyInteroperabilityVectors() throws Exception {
        String token = new RsaTokenSigner(RSA.getPrivate(), KID, "oficina-staging-customer", "oficina-staging-api")
                .emitir(new ClienteSnapshot(ID, "", true, "", 1), NOW);
        var refresh = claims(true); refresh.put("token_use", "refresh");
        var algorithm = header(true); algorithm.put("alg", "HS256");
        String[] p = token.split("\\."); p[2] = (p[2].startsWith("A") ? "B" : "A") + p[2].substring(1);
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/f3-contract-fixture.json").toFile(), Map.of(
                "publicKey", Base64.getEncoder().encodeToString(RSA.getPublic().getEncoded()), "kid", KID,
                "now", NOW.toString(), "valid", token, "tampered", String.join(".", p),
                "refresh", raw(refresh, header(true), true), "algorithm", raw(claims(true), algorithm, true)));
    }

    @Test void signerImplementsExactCustomerContract() {
        var signer = new RsaTokenSigner(RSA.getPrivate(), KID, "oficina-staging-customer", "oficina-staging-api");
        String token = signer.emitir(new ClienteSnapshot(ID, "52998224725", true, "fixture@example.invalid", 1), NOW);
        var decoded = Jwts.parser().clock(() -> Date.from(NOW)).verifyWith(RSA.getPublic()).build().parseSignedClaims(token);
        assertThat(decoded.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(decoded.getHeader().getKeyId()).isEqualTo(KID);
        assertThat(decoded.getPayload().keySet()).containsExactlyInAnyOrder("iss", "aud", "sub", "principal_type", "token_use", "identity_version", "scopes", "iat", "exp");
        assertThat(decoded.getPayload().getIssuer()).isEqualTo("oficina-staging-customer");
        assertThat(decoded.getPayload().getAudience()).containsExactly("oficina-staging-api");
        assertThat(decoded.getPayload().getSubject()).isEqualTo(ID.toString());
        assertThat(decoded.getPayload().get("principal_type")).isEqualTo("customer");
        assertThat(decoded.getPayload().get("token_use")).isEqualTo("access");
        assertThat(((Number) decoded.getPayload().get("identity_version")).longValue()).isEqualTo(1L);
        assertThat(decoded.getPayload().get("scopes")).isEqualTo(List.of("orders:read:self", "orders:decide:self"));
        assertThat(decoded.getPayload().getIssuedAt().toInstant()).isEqualTo(NOW);
        assertThat(decoded.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plusSeconds(900));
        var verifier = new CustomerTokenVerifier(Map.of(KID, RSA.getPublic()), "oficina-staging-customer", "oficina-staging-api", CLOCK);
        assertThat(verifier.verificar(token)).isEqualTo(new VerifiedPrincipal("customer", ID.toString(), Set.of("orders:read:self", "orders:decide:self")));
    }

    @Test void routePolicyUsesExplicitV2Grants() {
        var policy = new RoutePolicy();
        var customer = new VerifiedPrincipal("customer", ID.toString(), Set.of("orders:read:self"));
        assertThat(policy.permite(customer, "POST /api/ordens-servico")).isFalse();
        assertThat(policy.permite(customer, "GET /api/ordens-servico/{numero}/acompanhamento")).isTrue();
        assertThat(policy.permite(customer, "POST /api/ordens-servico/{numero}/aprovar")).isFalse();
        assertThat(policy.permite(new VerifiedPrincipal("staff", "staff@example.invalid", Set.of("ADMIN")), "GET /api/admin/relatorios/ordens")).isTrue();
        assertThat(policy.permite(new VerifiedPrincipal("staff", "staff@example.invalid", Set.of("MECANICO")), "GET /api/admin/relatorios/ordens")).isFalse();
        assertThat(policy.permite(customer, "$default")).isFalse();
    }
}
