package com.oficina.functions.bootstrap;

import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FunctionFactoryTest {
    @Test void authorizerCompositionNeedsOnlyPublicVerificationAndPolicyInputs() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
        var publicKey = keys.generateKeyPair().getPublic();
        Map<String, String> authorizerOnly = Map.of(
                "JWT_ISSUER", "https://issuer.example.invalid/staging",
                "JWT_AUDIENCE", "oficina-api-staging",
                "CUSTOMER_KEY_ID", "customer-2026-01",
                "CUSTOMER_PUBLIC_KEY_B64", Base64.getEncoder().encodeToString(publicKey.getEncoded()),
                "STAFF_KEY_ID", "staff-2026-01",
                "STAFF_HMAC_SECRET_B64", Base64.getEncoder().encodeToString(new byte[32]));

        assertThat(FunctionFactory.authorizerFrom(authorizerOnly)).isNotNull();
        assertThat(authorizerOnly).doesNotContainKeys("CUSTOMER_PRIVATE_KEY_B64", "DB_HOST", "DB_PASSWORD", "CHALLENGE_TABLE", "OTP_SENDER");
    }
}
