package com.oficina.functions.bootstrap;

import com.oficina.functions.adapter.aws.DynamoDesafioStore;
import com.oficina.functions.adapter.aws.SesEnviarCodigo;
import com.oficina.functions.adapter.jdbc.JdbcClienteLookup;
import com.oficina.functions.auth.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/** Plain Lambda composition root.  It deliberately has no Spring, Hibernate or JPA dependency. */
public final class FunctionFactory {
    private final CriarDesafio criarDesafio;
    private final VerificarDesafio verificarDesafio;
    private final Authorizer authorizer;

    public FunctionFactory(CriarDesafio criarDesafio, VerificarDesafio verificarDesafio, Authorizer authorizer) {
        this.criarDesafio = Objects.requireNonNull(criarDesafio);
        this.verificarDesafio = Objects.requireNonNull(verificarDesafio);
        this.authorizer = Objects.requireNonNull(authorizer);
    }
    private FunctionFactory(CriarDesafio criarDesafio, VerificarDesafio verificarDesafio) {
        this.criarDesafio = Objects.requireNonNull(criarDesafio);
        this.verificarDesafio = Objects.requireNonNull(verificarDesafio);
        this.authorizer = null;
    }
    public CriarDesafio criarDesafio() { return criarDesafio; }
    public VerificarDesafio verificarDesafio() { return verificarDesafio; }
    public Authorizer authorizer() { return authorizer; }

    /** Composition for the two OTP endpoints only. It owns the private key and delivery dependencies. */
    public static FunctionFactory authenticationFromEnvironment() {
        try {
            return authenticationFrom(System.getenv());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Authentication function configuration unavailable");
        }
    }

    static FunctionFactory authenticationFrom(Map<String, String> environment) {
        try {
            Clock clock = Clock.systemUTC();
            ConnectionProvider provider = new ConnectionProvider(new ConnectionProvider.Config(required(environment, "DB_HOST"), integer(environment, "DB_PORT"),
                    required(environment, "DB_NAME"), required(environment, "DB_USER"), required(environment, "DB_PASSWORD"), Path.of(required(environment, "DB_CA_PATH")).toAbsolutePath()));
            ClienteLookup lookup = new JdbcClienteLookup(provider);
            OtpHasher hasher = new OtpHasher();
            ClientOverrideConfiguration sdk = ClientOverrideConfiguration.builder().retryPolicy(RetryPolicy.builder().numRetries(2).build()).build();
            DynamoDbClient dynamo = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(sdk).build();
            SesV2Client ses = SesV2Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(sdk).build();
            DesafioStore store = new DynamoDesafioStore(dynamo, required(environment, "CHALLENGE_TABLE"), hasher, clock);
            EnviarCodigo email = new SesEnviarCodigo(ses, required(environment, "OTP_SENDER"));
            PrivateKey privateKey = privateKey(required(environment, "CUSTOMER_PRIVATE_KEY_B64"));
            String issuer = required(environment, "JWT_ISSUER"), audience = required(environment, "JWT_AUDIENCE"), kid = required(environment, "CUSTOMER_KEY_ID");
            TokenSigner signer = new RsaTokenSigner(privateKey, kid, issuer, audience);
            CriarDesafio criar = new CriarDesafio(lookup, store, email, new SecureCodigoGenerator(), hasher, clock);
            VerificarDesafio verificar = new VerificarDesafio(lookup, store, signer, clock);
            StructuredLog.coldStart("auth-functions");
            return new FunctionFactory(criar, verificar);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Authentication function configuration unavailable");
        }
    }

    /** Composition for the gateway authorizer only; it must never receive signing, database or delivery configuration. */
    public static Authorizer authorizerFromEnvironment() {
        return authorizerFrom(System.getenv());
    }

    static Authorizer authorizerFrom(Map<String, String> environment) {
        try {
            Clock clock = Clock.systemUTC();
            String issuer = required(environment, "JWT_ISSUER"), audience = required(environment, "JWT_AUDIENCE"), kid = required(environment, "CUSTOMER_KEY_ID");
            PublicKey publicKey = publicKey(required(environment, "CUSTOMER_PUBLIC_KEY_B64"));
            CustomerTokenVerifier customer = new CustomerTokenVerifier(Map.of(kid, publicKey), issuer, audience, clock);
            byte[] secret = Base64.getDecoder().decode(required(environment, "STAFF_HMAC_SECRET_B64"));
            StaffTokenVerifier staff = new StaffTokenVerifier(Map.of(required(environment, "STAFF_KEY_ID"), new SecretKeySpec(secret, "HmacSHA256")), issuer, audience, clock);
            StructuredLog.coldStart("http-authorizer");
            return new Authorizer(customer, staff, new RoutePolicy());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Authorizer function configuration unavailable");
        }
    }

    private static String required(Map<String, String> environment, String name) { String value = environment.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(); return value; }
    private static int integer(Map<String, String> environment, String name) { try { return Integer.parseInt(required(environment, name)); } catch (NumberFormatException e) { throw new IllegalArgumentException(); } }
    private static PrivateKey privateKey(String base64) { try { return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))); } catch (Exception e) { throw new IllegalArgumentException(); } }
    private static PublicKey publicKey(String base64) { try { return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64))); } catch (Exception e) { throw new IllegalArgumentException(); } }
}
