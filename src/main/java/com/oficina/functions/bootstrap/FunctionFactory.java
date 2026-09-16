package com.oficina.functions.bootstrap;

import com.oficina.functions.adapter.aws.DynamoDesafioStore;
import com.oficina.functions.adapter.aws.SesEnviarCodigo;
import com.oficina.functions.adapter.jdbc.JdbcClienteLookup;
import com.oficina.functions.adapter.jdbc.JdbcDestinatarioLookup;
import com.oficina.functions.adapter.aws.DynamoDeliveryLedger;
import com.oficina.functions.adapter.aws.SesStatusEmailSender;
import com.oficina.functions.auth.*;
import com.oficina.functions.notification.NotificarStatus;
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
import java.nio.charset.StandardCharsets;

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
            return authenticationFrom(resolvedEnvironment(System.getenv()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Authentication function configuration unavailable");
        }
    }

    /** Challenge creation has no signing capability and never resolves the customer private-key secret. */
    public static CriarDesafio challengeFromEnvironment() {
        try { return challengeFrom(resolvedEnvironment(without(System.getenv(), "CUSTOMER_SIGNING_SECRET_ARN"))); }
        catch (RuntimeException exception) { throw new IllegalStateException("Challenge function configuration unavailable"); }
    }
    static CriarDesafio challengeFrom(Map<String, String> environment) {
        return challengeFrom(environment, dynamoClient(), sesClient());
    }
    static CriarDesafio challengeFrom(Map<String, String> environment, DynamoDbClient dynamo, SesV2Client ses) {
        Clock clock = Clock.systemUTC();
        ConnectionProvider provider = provider(environment);
        OtpHasher hasher = new OtpHasher();
        DesafioStore store = new DynamoDesafioStore(dynamo, required(environment, "CHALLENGE_TABLE"), hasher, clock);
        EnviarCodigo email = new SesEnviarCodigo(ses, required(environment, "OTP_SENDER"));
        StructuredLog.coldStart("cpf-challenge");
        return new CriarDesafio(new JdbcClienteLookup(provider), store, email, new SecureCodigoGenerator(), hasher, clock);
    }

    /** Verification alone receives the customer signing key and has no SES capability. */
    public static VerificarDesafio verificationFromEnvironment() {
        try { return verificationFrom(resolvedEnvironment(System.getenv())); }
        catch (RuntimeException exception) { throw new IllegalStateException("Verification function configuration unavailable"); }
    }
    static VerificarDesafio verificationFrom(Map<String, String> environment) {
        return verificationFrom(environment, dynamoClient());
    }
    static VerificarDesafio verificationFrom(Map<String, String> environment, DynamoDbClient dynamo) {
        Clock clock = Clock.systemUTC();
        ConnectionProvider provider = provider(environment);
        OtpHasher hasher = new OtpHasher();
        DesafioStore store = new DynamoDesafioStore(dynamo, required(environment, "CHALLENGE_TABLE"), hasher, clock);
        PrivateKey privateKey = privateKey(required(environment, "CUSTOMER_PRIVATE_KEY_B64"));
        JwtTrust customerTrust = trust(environment, "CUSTOMER", "customer");
        TokenSigner signer = new RsaTokenSigner(privateKey, required(environment, "CUSTOMER_KEY_ID"), customerTrust.issuer(), customerTrust.audience());
        StructuredLog.coldStart("cpf-verification");
        return new VerificarDesafio(new JdbcClienteLookup(provider), store, signer, clock);
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
            JwtTrust customerTrust = trust(environment, "CUSTOMER", "customer");
            TokenSigner signer = new RsaTokenSigner(privateKey, required(environment, "CUSTOMER_KEY_ID"), customerTrust.issuer(), customerTrust.audience());
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
        return authorizerFrom(resolvedEnvironment(System.getenv()));
    }

    static Authorizer authorizerFrom(Map<String, String> environment) {
        try {
            Clock clock = Clock.systemUTC();
            JwtTrust customerTrust = trust(environment, "CUSTOMER", "customer");
            JwtTrust staffTrust = trust(environment, "STAFF", "staff");
            if (!customerTrust.environment().equals(staffTrust.environment())) throw new IllegalArgumentException();
            String kid = required(environment, "CUSTOMER_KEY_ID");
            PublicKey publicKey = publicKey(required(environment, "CUSTOMER_PUBLIC_KEY_B64"));
            CustomerTokenVerifier customer = new CustomerTokenVerifier(Map.of(kid, publicKey), customerTrust.issuer(), customerTrust.audience(), clock);
            byte[] secret = required(environment, "STAFF_HMAC_SECRET").getBytes(StandardCharsets.UTF_8);
            StaffTokenVerifier staff = new StaffTokenVerifier(Map.of(required(environment, "STAFF_KEY_ID"), new SecretKeySpec(secret, "HmacSHA256")), staffTrust.issuer(), staffTrust.audience(), clock);
            StructuredLog.coldStart("http-authorizer");
            return new Authorizer(customer, staff, new RoutePolicy());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Authorizer function configuration unavailable");
        }
    }

    /** Composition for the FIFO notification worker. It owns only its read model, ledger and SES transport. */
    public static NotificarStatus notificationFromEnvironment() {
        try {
            Map<String, String> environment = resolvedEnvironment(System.getenv());
            ConnectionProvider provider = new ConnectionProvider(new ConnectionProvider.Config(required(environment, "DB_HOST"), integer(environment, "DB_PORT"),
                    required(environment, "DB_NAME"), required(environment, "DB_USER"), required(environment, "DB_PASSWORD"), Path.of(required(environment, "DB_CA_PATH")).toAbsolutePath()));
            ClientOverrideConfiguration sdk = ClientOverrideConfiguration.builder().retryPolicy(RetryPolicy.builder().numRetries(2).build()).build();
            DynamoDbClient dynamo = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(sdk).build();
            SesV2Client ses = SesV2Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(sdk).build();
            StructuredLog.coldStart("status-notification");
            return new NotificarStatus(new JdbcDestinatarioLookup(provider), new DynamoDeliveryLedger(dynamo, required(environment, "DELIVERY_TABLE")),
                    new SesStatusEmailSender(ses, required(environment, "STATUS_SENDER")), Clock.systemUTC());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Notification function configuration unavailable");
        }
    }

    private static Map<String, String> resolvedEnvironment(Map<String, String> environment) { return SecretResolver.fromEnvironment(environment).values(); }
    private static Map<String, String> without(Map<String, String> environment, String excluded) {
        Map<String, String> copy = new java.util.HashMap<>(environment); copy.remove(excluded); return copy;
    }
    private static ConnectionProvider provider(Map<String, String> environment) {
        return new ConnectionProvider(new ConnectionProvider.Config(required(environment, "DB_HOST"), integer(environment, "DB_PORT"), required(environment, "DB_NAME"),
                required(environment, "DB_USER"), required(environment, "DB_PASSWORD"), Path.of(required(environment, "DB_CA_PATH")).toAbsolutePath()));
    }
    private static ClientOverrideConfiguration sdkConfig() { return ClientOverrideConfiguration.builder().retryPolicy(RetryPolicy.builder().numRetries(2).build()).build(); }
    private static DynamoDbClient dynamoClient() { return DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(sdkConfig()).build(); }
    private static SesV2Client sesClient() { return SesV2Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).overrideConfiguration(sdkConfig()).build(); }

    private static String required(Map<String, String> environment, String name) { String value = environment.get(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(); return value; }
    private static int integer(Map<String, String> environment, String name) { try { return Integer.parseInt(required(environment, name)); } catch (NumberFormatException e) { throw new IllegalArgumentException(); } }
    private static JwtTrust trust(Map<String, String> environment, String prefix, String domain) {
        String issuer = required(environment, prefix + "_JWT_ISSUER");
        String audience = required(environment, prefix + "_JWT_AUDIENCE");
        String marker = "oficina-";
        if (!issuer.startsWith(marker) || !issuer.endsWith("-" + domain) || !audience.startsWith(marker) || !audience.endsWith("-api"))
            throw new IllegalArgumentException();
        String issuerEnvironment = issuer.substring(marker.length(), issuer.length() - domain.length() - 1);
        String audienceEnvironment = audience.substring(marker.length(), audience.length() - "-api".length());
        if (!issuerEnvironment.equals(audienceEnvironment) || !(issuerEnvironment.equals("staging") || issuerEnvironment.equals("production")))
            throw new IllegalArgumentException();
        return new JwtTrust(issuer, audience, issuerEnvironment);
    }
    private record JwtTrust(String issuer, String audience, String environment) { }
    private static PrivateKey privateKey(String base64) { try { return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))); } catch (Exception e) { throw new IllegalArgumentException(); } }
    private static PublicKey publicKey(String base64) { try { return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64))); } catch (Exception e) { throw new IllegalArgumentException(); } }
}
