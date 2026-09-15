package com.oficina.functions.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class Phase3ContractTest {

    private static final Path CONTRACTS = Path.of("contracts/phase3-v1");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void eventContainsExactReferencesWithoutContactOrSecurityData() throws Exception {
        Path fixture = CONTRACTS.resolve("status-event.json");
        JsonNode event = MAPPER.readTree(fixture.toFile());

        assertThat(fieldNames(event)).containsExactlyInAnyOrder(
                "eventId", "eventType", "schemaVersion", "ordemId", "numero", "clienteId",
                "versaoIdentidadeCliente", "sequencia", "statusAnterior", "statusNovo",
                "ocorridoEm", "correlationId", "traceparent");
        assertThat(event.path("eventId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000101");
        assertThat(event.path("eventType").textValue()).isEqualTo("StatusOrdemServicoRegistrado");
        assertThat(event.path("schemaVersion").isIntegralNumber()).isTrue();
        assertThat(event.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(event.path("ordemId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000201");
        assertThat(event.path("numero").longValue()).isEqualTo(1001L);
        assertThat(event.path("clienteId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000301");
        assertThat(event.path("versaoIdentidadeCliente").longValue()).isEqualTo(1L);
        assertThat(event.path("sequencia").longValue()).isEqualTo(1L);
        assertThat(event.get("statusAnterior").isNull()).isTrue();
        assertThat(event.path("statusNovo").textValue()).isEqualTo("RECEBIDA");
        assertThat(event.path("ocorridoEm").textValue()).isEqualTo("2026-09-15T12:00:00Z");
        assertThat(event.path("correlationId").textValue()).isEqualTo("00000000-0000-0000-0000-000000000401");
        assertThat(event.get("traceparent").isNull()).isTrue();
        assertThat(lowerCaseFieldNamesRecursively(event)).doesNotContain(
                "cpf", "email", "jwt", "token", "authorization", "contato", "telefone");
        assertThat(Files.size(fixture)).isLessThanOrEqualTo(8192L);
    }

    @Test
    void tokenClaimsPinEnvironmentTrustPurposeAndPermissions() throws Exception {
        JsonNode root = MAPPER.readTree(CONTRACTS.resolve("token-claims.json").toFile());
        assertThat(root.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(root.path("algorithms").path("customer").textValue()).isEqualTo("RS256");
        assertThat(root.path("algorithms").path("staff").textValue()).isEqualTo("HS256");
        assertEnvironment(root.path("environments").path("staging"), "staging");
        assertEnvironment(root.path("environments").path("production"), "production");
    }

    @Test
    void routeMatrixIsExplicitAndDefaultDeny() throws Exception {
        JsonNode root = MAPPER.readTree(CONTRACTS.resolve("routes.json").toFile());
        assertThat(root.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(root.path("defaultDecision").textValue()).isEqualTo("DENY");
        assertThat(root.path("routes")).hasSize(37);
        Set<String> keys = new HashSet<>();
        root.path("routes").forEach(route -> {
            String key = route.path("method").textValue() + " " + route.path("path").textValue();
            assertThat(keys.add(key)).as("duplicate route %s", key).isTrue();
            assertThat(route.path("path").textValue()).doesNotContain("**", "/*");
        });
        assertGrant(root, "GET /api/ordens-servico/{numero}/acompanhamento", "customer", Set.of(), Set.of("orders:read:self"));
        assertGrant(root, "POST /api/ordens-servico/{numero}/orcamento/decisao", "customer", Set.of(), Set.of("orders:decide:self"));
        assertGrant(root, "POST /api/ordens-servico/{numero}/orcamento/notificacao", "customer", Set.of(), Set.of("orders:decide:self"));
        assertGrant(root, "POST /api/ordens-servico/{numero}/aprovar", "customer", Set.of(), Set.of("orders:decide:self"));
        for (String publicRoute : Set.of("POST /api/auth/cpf/desafios", "POST /api/auth/cpf/verificar", "POST /api/auth/login", "GET /health")) {
            assertGrant(root, publicRoute, "anonymous", Set.of(), Set.of());
        }
        JsonNode retired = findRoute(root, "POST /api/ordens-servico/email/atualizar-status");
        assertThat(retired.path("decision").textValue()).isEqualTo("DENY");
        assertThat(retired.path("grants")).isEmpty();
    }

    @Test
    void vendoredFilesMatchSha256Manifest() throws Exception {
        List<String> lines = Files.readAllLines(CONTRACTS.resolve("SHA256SUMS"), StandardCharsets.UTF_8)
                .stream().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(5);
        assertThat(lines.stream().map(line -> line.split("  ", 2)[1])).containsExactlyInAnyOrder(
                "README.md", "lookup-views.md", "routes.json", "status-event.json", "token-claims.json");
        for (String line : lines) {
            String[] parts = line.split("  ", 2);
            assertThat(parts).hasSize(2);
            Path fixture = CONTRACTS.resolve(parts[1]);
            assertThat(fixture.normalize().startsWith(CONTRACTS.normalize())).isTrue();
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture))))
                    .isEqualTo(parts[0]);
        }
    }

    private static void assertEnvironment(JsonNode environment, String name) {
        String audience = "oficina-" + name + "-api";
        JsonNode customer = environment.path("customerAccess");
        assertThat(customer.path("iss").textValue()).isEqualTo("oficina-" + name + "-customer");
        assertThat(customer.path("aud").textValue()).isEqualTo(audience);
        assertThat(customer.path("principal_type").textValue()).isEqualTo("customer");
        assertThat(customer.path("token_use").textValue()).isEqualTo("access");
        assertThat(customer.path("sub").textValue()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(customer.path("identity_version").longValue()).isPositive();
        assertThat(customer.path("exp").longValue() - customer.path("iat").longValue()).isEqualTo(900L);
        assertThat(strings(customer.path("scopes"))).containsExactlyInAnyOrder("orders:read:self", "orders:decide:self");
        JsonNode access = environment.path("staffAccess");
        JsonNode refresh = environment.path("staffRefresh");
        assertThat(access.path("iss").textValue()).isEqualTo("oficina-" + name + "-staff");
        assertThat(access.path("aud").textValue()).isEqualTo(audience);
        assertThat(access.path("principal_type").textValue()).isEqualTo("staff");
        assertThat(access.path("token_use").textValue()).isEqualTo("access");
        assertThat(access.path("exp").longValue()).isGreaterThan(access.path("iat").longValue());
        assertThat(access.path("sub").textValue()).isEqualTo(refresh.path("sub").textValue()).contains("@");
        assertThat(refresh.path("iss").textValue()).isEqualTo("oficina-" + name + "-staff");
        assertThat(refresh.path("aud").textValue()).isEqualTo(audience);
        assertThat(refresh.path("principal_type").textValue()).isEqualTo("staff");
        assertThat(refresh.path("token_use").textValue()).isEqualTo("refresh");
        assertThat(refresh.path("exp").longValue()).isGreaterThan(refresh.path("iat").longValue());
    }

    private static void assertGrant(JsonNode root, String routeKey, String actor,
                                    Set<String> roles, Set<String> scopes) {
        JsonNode route = findRoute(root, routeKey);
        assertThat(route.path("decision").textValue()).isEqualTo("ALLOW");
        assertThat(StreamSupport.stream(route.path("grants").spliterator(), false)
                .anyMatch(grant -> actor.equals(grant.path("actor").textValue())
                        && strings(grant.path("roles")).equals(roles)
                        && strings(grant.path("scopes")).equals(scopes)))
                .as("grant for %s", routeKey).isTrue();
    }

    private static JsonNode findRoute(JsonNode root, String routeKey) {
        return StreamSupport.stream(root.path("routes").spliterator(), false)
                .filter(route -> routeKey.equals(route.path("method").textValue() + " " + route.path("path").textValue()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing route " + routeKey));
    }

    private static Set<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::textValue).collect(Collectors.toSet());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> lowerCaseFieldNamesRecursively(JsonNode node) {
        Set<String> names = new HashSet<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                names.add(field.getKey().toLowerCase());
                names.addAll(lowerCaseFieldNamesRecursively(field.getValue()));
            }
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(lowerCaseFieldNamesRecursively(child)));
        }
        return names;
    }
}
