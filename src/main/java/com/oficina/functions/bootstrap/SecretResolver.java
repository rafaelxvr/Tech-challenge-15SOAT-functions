package com.oficina.functions.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Resolves only declared Secrets Manager ARNs. Values stay in memory except the RDS trust anchor. */
final class SecretResolver {
    private static final Set<String> ARN_SETTINGS = Set.of("DATABASE_SECRET_ARN", "CUSTOMER_SIGNING_SECRET_ARN",
            "AUTHORIZER_TRUST_SECRET_ARN", "RDS_CA_CERT_SECRET_ARN");
    private static final Map<String, Set<String>> SECRET_FIELDS = Map.of(
            "DATABASE_SECRET_ARN", Set.of("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD"),
            "CUSTOMER_SIGNING_SECRET_ARN", Set.of("CUSTOMER_PRIVATE_KEY_B64"),
            "AUTHORIZER_TRUST_SECRET_ARN", Set.of("CUSTOMER_PUBLIC_KEY_B64", "STAFF_HMAC_SECRET"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, String> values;
    private SecretResolver(Map<String, String> values) { this.values = Map.copyOf(values); }

    static SecretResolver fromEnvironment(Map<String, String> environment) {
        Map<String, String> values = new HashMap<>(environment);
        List<String> configured = ARN_SETTINGS.stream().filter(environment::containsKey).toList();
        if (configured.isEmpty()) return new SecretResolver(values); // direct local/test configuration
        SecretsManagerClient client = SecretsManagerClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .apiCallTimeout(Duration.ofSeconds(5)).retryPolicy(RetryPolicy.builder().numRetries(2).build()).build()).build();
        return from(environment, client);
    }

    static SecretResolver from(Map<String, String> environment, SecretsManagerClient client) {
        Map<String, String> values = new HashMap<>(environment);
        for (String setting : ARN_SETTINGS) {
            String arn = environment.get(setting);
            if (arn == null || arn.isBlank()) continue;
            if (!arn.matches("arn:aws[a-z-]*:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+"))
                throw new IllegalArgumentException("Invalid declared secret ARN");
            String raw = client.getSecretValue(GetSecretValueRequest.builder().secretId(arn).build()).secretString();
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Secret value unavailable");
            if ("RDS_CA_CERT_SECRET_ARN".equals(setting)) materializeCa(required(values, "DB_CA_PATH"), raw);
            else mergeObject(values, raw, SECRET_FIELDS.get(setting));
        }
        return new SecretResolver(values);
    }

    Map<String, String> values() { return values; }
    private static void mergeObject(Map<String, String> destination, String secret, Set<String> allowed) {
        try {
            JsonNode object = JSON.readTree(secret);
            if (!object.isObject()) throw new IllegalArgumentException();
            object.fields().forEachRemaining(entry -> {
                if (!allowed.contains(entry.getKey()) || !entry.getValue().isTextual() || entry.getValue().asText().isBlank()) throw new IllegalArgumentException();
                destination.put(entry.getKey(), entry.getValue().asText());
            });
            if (!destination.keySet().containsAll(allowed)) throw new IllegalArgumentException();
        } catch (RuntimeException | java.io.IOException exception) { throw new IllegalArgumentException("Secret JSON unavailable"); }
    }
    private static void materializeCa(String configuredPath, String pem) {
        if (!pem.startsWith("-----BEGIN CERTIFICATE-----") || !pem.contains("-----END CERTIFICATE-----"))
            throw new IllegalArgumentException("Invalid RDS CA certificate");
        // Lambda's deployed code and layer locations are read-only. IaC must set DB_CA_PATH under /tmp/oficina.
        if (!configuredPath.matches("/tmp/oficina/[A-Za-z0-9_.-]+\\.pem"))
            throw new IllegalArgumentException("RDS CA target must be a writable /tmp/oficina PEM path");
        try {
            Path target = Path.of(configuredPath).toAbsolutePath().normalize();
            Files.createDirectories(target.getParent());
            Files.writeString(target, pem, StandardCharsets.US_ASCII);
        } catch (Exception exception) { throw new IllegalArgumentException("RDS CA unavailable"); }
    }
    private static String required(Map<String, String> values, String name) { String value = values.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(); return value; }
}
