package com.oficina.functions.bootstrap;

import com.oficina.functions.auth.AutenticacaoException;
import com.oficina.functions.auth.ClienteSnapshot;
import com.oficina.functions.auth.RsaTokenSigner;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionFactoryTest {
    @Test void authorizerCompositionNeedsOnlyPublicVerificationAndPolicyInputs() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
        var publicKey = keys.generateKeyPair().getPublic();
        Map<String, String> authorizerOnly = Map.of(
                "CUSTOMER_JWT_ISSUER", "oficina-staging-customer",
                "CUSTOMER_JWT_AUDIENCE", "oficina-staging-api",
                "CUSTOMER_KEY_ID", "customer-2026-01",
                "CUSTOMER_PUBLIC_KEY_B64", Base64.getEncoder().encodeToString(publicKey.getEncoded()),
                "STAFF_JWT_ISSUER", "oficina-staging-staff",
                "STAFF_JWT_AUDIENCE", "oficina-staging-api",
                "STAFF_KEY_ID", "staff-2026-01",
                "STAFF_HMAC_SECRET", "staff-secret-that-is-at-least-thirty-two-bytes");

        assertThat(FunctionFactory.authorizerFrom(authorizerOnly)).isNotNull();
        assertThat(authorizerOnly).doesNotContainKeys("CUSTOMER_PRIVATE_KEY_B64", "DB_HOST", "DB_PASSWORD", "CHALLENGE_TABLE", "OTP_SENDER", "JWT_ISSUER", "JWT_AUDIENCE");
    }

    @Test void authorizerAcceptsCanonicalCustomerAndStaffAccessButRejectsCrossDomainAndRefresh() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String staffSecret = "staff-secret-that-is-at-least-thirty-two-bytes";
        Map<String, String> environment = Map.of(
                "CUSTOMER_JWT_ISSUER", "oficina-staging-customer", "CUSTOMER_JWT_AUDIENCE", "oficina-staging-api",
                "CUSTOMER_KEY_ID", "customer-2026-01", "CUSTOMER_PUBLIC_KEY_B64", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                "STAFF_JWT_ISSUER", "oficina-staging-staff", "STAFF_JWT_AUDIENCE", "oficina-staging-api",
                "STAFF_KEY_ID", "staff-2026-01", "STAFF_HMAC_SECRET", staffSecret);
        var authorizer = FunctionFactory.authorizerFrom(environment);
        Instant now = Instant.now();
        String customer = new RsaTokenSigner(pair.getPrivate(), "customer-2026-01", "oficina-staging-customer", "oficina-staging-api")
                .emitir(new ClienteSnapshot(UUID.randomUUID(), "39053344705", true, "registered@example.invalid", 1), now);
        SecretKeySpec staffKey = new SecretKeySpec(staffSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        String staff = staffToken(staffKey, "oficina-staging-staff", "access", now);
        String refresh = staffToken(staffKey, "oficina-staging-staff", "refresh", now);
        String crossDomain = new RsaTokenSigner(pair.getPrivate(), "customer-2026-01", "oficina-staging-staff", "oficina-staging-api")
                .emitir(new ClienteSnapshot(UUID.randomUUID(), "39053344705", true, "registered@example.invalid", 1), now);

        assertThat(authorizer.autorizar("Bearer " + customer, "GET /api/ordens-servico/{numero}/acompanhamento")).isTrue();
        assertThat(authorizer.autorizar("Bearer " + staff, "GET /api/admin/relatorios/ordens")).isTrue();
        assertThatThrownBy(() -> authorizer.autorizar("Bearer " + crossDomain, "GET /api/ordens-servico/{numero}/acompanhamento"))
                .isInstanceOf(AutenticacaoException.class);
        assertThatThrownBy(() -> authorizer.autorizar("Bearer " + refresh, "GET /api/admin/relatorios/ordens"))
                .isInstanceOf(AutenticacaoException.class);
    }

    private static String staffToken(SecretKeySpec key, String issuer, String use, Instant now) {
        return Jwts.builder().header().keyId("staff-2026-01").and().issuer(issuer).audience().add("oficina-staging-api").and()
                .subject("staff.fixture@example.invalid").claim("principal_type", "staff").claim("token_use", use)
                .claim("roles", java.util.List.of("ADMIN")).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(900)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }
}
