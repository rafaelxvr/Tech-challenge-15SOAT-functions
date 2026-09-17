package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.oficina.functions.auth.*;
import com.oficina.functions.support.InMemoryDesafioStore;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class HttpHandlersTest {
    private static final UUID CUSTOMER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    @Test void challengeUsesGatewaySourceAndReturnsGeneric202Envelope() {
        Fixture fixture = new Fixture();
        APIGatewayV2HTTPEvent event = event("{\"cpf\":\"390.533.447-05\"}");
        var response = new CriarDesafioHandler(fixture.criar).handleRequest(event, null);
        assertThat(response.getStatusCode()).isEqualTo(202);
        assertThat(response.getHeaders()).containsEntry("X-Correlation-Id", "fixture-123");
        assertThat(response.getBody()).contains("expiraEmSegundos", "300").doesNotContain("123456", "example.invalid", "accessToken");
        assertThat(fixture.source).isEqualTo(sha256("origem:203.0.113.10"));
    }
    @Test void verifySuccessAndUnsafeBodiesAreSafe() {
        Fixture fixture = new Fixture();
        UUID id = fixture.criar.executar("39053344705", "203.0.113.10").desafioId();
        var success = new VerificarDesafioHandler(fixture.verificar).handleRequest(event("{\"desafioId\":\"" + id + "\",\"codigo\":\"123456\"}"), null);
        assertThat(success.getStatusCode()).isEqualTo(200);
        assertThat(success.getBody()).contains("signed-token");
        var bad = new CriarDesafioHandler(fixture.criar).handleRequest(event("{\"cpf\":\"39053344705\",\"sourceIp\":\"attacker\"}"), null);
        assertThat(bad.getStatusCode()).isEqualTo(400);
        assertThat(bad.getBody()).doesNotContain("attacker", "exception");
    }
    @Test void authorizerDistinguishesInvalidAndDenied() {
        var pair = keyPair(); Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
        String token = new RsaTokenSigner(pair.getPrivate(), "customer-1", "issuer", "audience")
                .emitir(new ClienteSnapshot(CUSTOMER, "39053344705", true, "registered@example.invalid", 1), clock.instant());
        Authorizer authorizer = new Authorizer(new CustomerTokenVerifier(Map.of("customer-1", pair.getPublic()), "issuer", "audience", clock),
                new StaffTokenVerifier(Map.of("staff-1", new javax.crypto.spec.SecretKeySpec(new byte[32], "HmacSHA256")), "issuer", "audience", clock), new RoutePolicy());
        AuthorizerHandler handler = new AuthorizerHandler(authorizer);
        assertThat(handler.handleRequest(Map.of("routeKey", "GET /api/ordens-servico/{numero}/acompanhamento"), null)).containsEntry("errorMessage", "Unauthorized");
        assertThat(handler.handleRequest(Map.of("routeKey", "POST /api/ordens-servico", "identitySource", List.of("Bearer " + token)), null))
                .containsEntry("isAuthorized", false);
    }
    private static java.security.KeyPair keyPair() { try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); } catch (Exception e) { throw new AssertionError(e); } }
    private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new AssertionError(e); } }
    private static APIGatewayV2HTTPEvent event(String body) {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setBody(body); event.setHeaders(Map.of("content-type", "application/json", "x-correlation-id", "fixture-123"));
        var http = new APIGatewayV2HTTPEvent.RequestContext.Http(); http.setSourceIp("203.0.113.10");
        var context = new APIGatewayV2HTTPEvent.RequestContext(); context.setHttp(http); event.setRequestContext(context);
        return event;
    }
    private static final class Fixture {
        final OtpHasher hasher = new OtpHasher(); final InMemoryDesafioStore store = new InMemoryDesafioStore(hasher);
        String source;
        final ClienteSnapshot customer = new ClienteSnapshot(CUSTOMER, "39053344705", true, "registered@example.invalid", 1);
        final ClienteLookup lookup = new ClienteLookup() { public Optional<ClienteSnapshot> porCpf(String cpf) { return Optional.of(customer); } public Optional<ClienteSnapshot> porId(UUID id) { return Optional.of(customer); } };
        final CriarDesafio criar = new CriarDesafio(lookup, new DesafioStore() {
            public boolean emitir(Desafio d, String s, Instant i) { source = s; return store.emitir(d, s, i); }
            public Optional<Desafio> verificarEConsumir(UUID i, String c, Instant n) { return store.verificarEConsumir(i,c,n); } public void invalidar(UUID i) { store.invalidar(i); }
        }, (email, code) -> { }, () -> "123456", hasher, Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));
        final VerificarDesafio verificar = new VerificarDesafio(lookup, store, (c, now) -> "signed-token", Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC));
    }
}
