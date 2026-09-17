package com.oficina.functions.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SecretResolverTest {
    private static final String DB_ARN = "arn:aws:secretsmanager:us-east-1:123456789012:secret:database-abc";
    private static final String CA_ARN = "arn:aws:secretsmanager:us-east-1:123456789012:secret:rds-ca-abc";

    @Test void resolvesOnlyDeclaredArnJsonAndMaterializesTrustAnchorWithoutLogging() throws Exception {
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        String secret = "sensitive-db-password";
        when(client.getSecretValue(any(GetSecretValueRequest.class))).thenAnswer(call -> {
            String arn = call.getArgument(0, GetSecretValueRequest.class).secretId();
            return GetSecretValueResponse.builder().secretString(arn.equals(DB_ARN)
                    ? "{\"DB_HOST\":\"db.example.invalid\",\"DB_PORT\":\"5432\",\"DB_NAME\":\"oficina\",\"DB_USER\":\"functions\",\"DB_PASSWORD\":\"" + secret + "\"}"
                    : "-----BEGIN CERTIFICATE-----\npublic-ca\n-----END CERTIFICATE-----").build();
        });
        String configuredCa = "/tmp/oficina/rds-ca-test.pem";
        Path ca = Path.of(configuredCa).toAbsolutePath();
        Map<String, String> environment = Map.of("DATABASE_SECRET_ARN", DB_ARN, "RDS_CA_CERT_SECRET_ARN", CA_ARN, "DB_CA_PATH", configuredCa);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); PrintStream previous = System.out;
        try {
            System.setOut(new PrintStream(output));
            Map<String, String> values = SecretResolver.from(environment, client).values();
            StructuredLog.coldStart("test");
            assertThat(values).containsEntry("DB_PASSWORD", secret).containsEntry("DB_HOST", "db.example.invalid");
            assertThat(Files.readString(ca)).contains("BEGIN CERTIFICATE");
        } finally { System.setOut(previous); Files.deleteIfExists(ca); }
        ArgumentCaptor<GetSecretValueRequest> requests = ArgumentCaptor.forClass(GetSecretValueRequest.class);
        verify(client, times(2)).getSecretValue(requests.capture());
        assertThat(requests.getAllValues()).extracting(GetSecretValueRequest::secretId).containsExactlyInAnyOrder(DB_ARN, CA_ARN);
        assertThat(output.toString()).doesNotContain(secret, DB_ARN, CA_ARN, "BEGIN CERTIFICATE");
    }

    @Test void rejectsAReadOnlyOrUnsafeCertificateTargetBeforeWriting(@TempDir Path directory) {
        SecretsManagerClient client = mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class))).thenReturn(GetSecretValueResponse.builder()
                .secretString("-----BEGIN CERTIFICATE-----\npublic-ca\n-----END CERTIFICATE-----").build());
        Path unsafe = directory.resolve("rds-ca.pem");
        assertThatThrownBy(() -> SecretResolver.from(Map.of(
                "RDS_CA_CERT_SECRET_ARN", CA_ARN, "DB_CA_PATH", unsafe.toString()), client))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(unsafe).doesNotExist();
    }
}
