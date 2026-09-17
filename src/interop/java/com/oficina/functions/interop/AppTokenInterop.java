package com.oficina.functions.interop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.config.JwtProperties;
import com.oficina.entity.Cliente;
import com.oficina.functions.auth.ClienteSnapshot;
import com.oficina.functions.auth.RsaTokenSigner;
import com.oficina.repository.ClienteRepository;
import com.oficina.security.CustomerTokenValidator;
import com.oficina.security.TipoPrincipal;
import io.jsonwebtoken.Jwts;
import org.springframework.security.authentication.BadCredentialsException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;

/** Standalone integration harness: compiled by scripts/verify-app-token-interop.ps1, never shipped. */
public final class AppTokenInterop {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final String KID = "fresh-fun-app-interop-key";

    private AppTokenInterop() {}

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 17) throw new AssertionError("Run this harness with Java 17");
        if (args.length != 3) throw new IllegalArgumentException("APP classes, FUN classes, evidence directory required");
        Path appClasses = Path.of(args[0]).toRealPath();
        Path funClasses = Path.of(args[1]).toRealPath();
        Path evidence = Path.of(args[2]).toRealPath();
        System.out.println("JAVA_RUNTIME=" + Runtime.version());
        System.out.println("RUNTIME_CLASSPATH=" + System.getProperty("java.class.path"));
        // Assert actual JVM code sources, not only a claimed classpath or a source filename.
        provenance(CustomerTokenValidator.class, appClasses);
        provenance(com.oficina.security.TokenVerification.class, appClasses);
        provenance(JwtProperties.class, appClasses);
        provenance(ClienteRepository.class, appClasses);
        provenance(Cliente.class, appClasses);
        provenance(RsaTokenSigner.class, funClasses);

        KeyPair rsa = Jwts.SIG.RS256.keyPair().build();
        String valid = new RsaTokenSigner(rsa.getPrivate(), KID, "oficina-staging-customer", "oficina-staging-api")
                .emitir(new ClienteSnapshot(CUSTOMER, "", true, "", 1), NOW);
        String[] parts = valid.split("\\.");
        Map<String, Object> claims = JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), new TypeReference<>() {});
        Map<String, Object> refreshClaims = new LinkedHashMap<>(claims);
        refreshClaims.put("token_use", "refresh");
        String refresh = sign(refreshClaims, rsa, false);
        // Real RS-to-HS confusion attack: treat the public RSA bytes as the HMAC secret.
        String confused = sign(claims, rsa, true);
        byte[] damaged = Base64.getUrlDecoder().decode(parts[2]);
        damaged[0] ^= 1;
        String tampered = parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(damaged);
        var vectors = new LinkedHashMap<String, String>();
        vectors.put("tampered", tampered);
        vectors.put("refresh", refresh);
        vectors.put("algorithm-confused", confused);
        Path fixture = evidence.resolve("public-fixture.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(fixture.toFile(), Map.of(
                "now", NOW.toString(), "kid", KID,
                "publicKey", Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded()),
                "valid", valid, "negativeVectors", vectors));
        // Retain this run's public-only vectors; never serialize the private key.
        System.out.println("PUBLIC_FIXTURE=" + fixture);
        System.out.println("PUBLIC_FIXTURE_SHA256=" + sha256(Files.readAllBytes(fixture)));

        ClienteRepository clientes = mock(ClienteRepository.class);
        Cliente cliente = mock(Cliente.class);
        when(clientes.findById(CUSTOMER)).thenReturn(Optional.of(cliente));
        when(cliente.isAtivo()).thenReturn(true);
        when(cliente.getVersaoIdentidade()).thenReturn(1L);
        var trust = new JwtProperties(null, 0, 0, null,
                new JwtProperties.CustomerTrust("oficina-staging-customer", "oficina-staging-api",
                        Map.of(KID, Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded()))));
        var validator = new CustomerTokenValidator(trust, clientes, Clock.fixed(NOW, ZoneOffset.UTC));
        var identity = validator.validar(valid);
        if (identity.tipo() != TipoPrincipal.CUSTOMER || !identity.id().equals(CUSTOMER)
                || identity.versaoIdentidade() != 1
                || !identity.permissoes().equals(Set.of("SCOPE_orders:read:self", "SCOPE_orders:decide:self")))
            throw new AssertionError("Incorrect accepted customer identity");
        verify(clientes).findById(CUSTOMER);
        verify(cliente).isAtivo();
        verify(cliente).getVersaoIdentidade();
        System.out.println("PASS APP CustomerTokenValidator accepts fresh FUN RsaTokenSigner token and rechecks identity");

        // Prove that the same token is rejected when the current APP identity changes.
        when(cliente.getVersaoIdentidade()).thenReturn(2L);
        expectInvalid(validator, valid);
        verify(clientes, times(2)).findById(CUSTOMER);
        System.out.println("PASS APP CustomerTokenValidator rechecks changed identity version for the same token");

        clearInvocations(clientes, cliente);
        for (var vector : vectors.entrySet()) {
            expectInvalid(validator, vector.getValue());
            verifyNoInteractions(clientes, cliente);
            System.out.println("PASS APP CustomerTokenValidator rejects " + vector.getKey() + " before database lookup");
        }
        System.out.println("INTEROP_RESULT=PASS");
    }

    private static void expectInvalid(CustomerTokenValidator validator, String token) {
        try {
            validator.validar(token);
            throw new AssertionError("APP accepted a rejected vector");
        } catch (BadCredentialsException expected) {
            if (!"Invalid credentials".equals(expected.getMessage())) throw new AssertionError("Unexpected APP boundary error");
        }
    }

    private static String sign(Map<String, Object> claims, KeyPair rsa, boolean confusion) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String content = encoder.encodeToString(JSON.writeValueAsBytes(Map.of("kid", KID, "alg", confusion ? "HS256" : "RS256")))
                + "." + encoder.encodeToString(JSON.writeValueAsBytes(claims));
        byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);
        byte[] signature;
        if (confusion) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rsa.getPublic().getEncoded(), "HmacSHA256"));
            signature = mac.doFinal(bytes);
        } else {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(rsa.getPrivate()); signer.update(bytes); signature = signer.sign();
        }
        return content + "." + encoder.encodeToString(signature);
    }

    private static void provenance(Class<?> type, Path expected) throws Exception {
        Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!Files.isSameFile(actual, expected)) throw new AssertionError("Unexpected code source for " + type.getName() + ": " + actual);
        byte[] bytes;
        try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (stream == null) throw new AssertionError("Missing class resource");
            bytes = stream.readAllBytes();
        }
        System.out.println("LOADED_CLASS=" + type.getName() + " SOURCE=" + actual + " SHA256=" + sha256(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
