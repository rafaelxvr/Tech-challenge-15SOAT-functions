package com.oficina.functions.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;

/** Exact API Gateway route keys, never URL-prefix or authenticated catch-all matching. */
public final class RoutePolicy {
    private record Grant(String actor, Set<String> roles, Set<String> scopes) {}
    private final Map<String, List<Grant>> routes;

    public RoutePolicy() {
        try (InputStream stream = RoutePolicy.class.getResourceAsStream("/contracts/phase3-v2/routes.json")) {
            if (stream == null) throw new IllegalArgumentException();
            JsonNode root = new ObjectMapper().readTree(stream);
            if (root.path("schemaVersion").asInt() != 1 || !root.path("defaultDecision").asText().equals("DENY")
                    || !root.path("routes").isArray()) throw new IllegalArgumentException();
            Map<String, List<Grant>> loaded = new HashMap<>();
            for (JsonNode route : root.path("routes")) {
                String key = route.path("method").asText() + " " + route.path("path").asText();
                if (key.contains("*") || loaded.containsKey(key)) throw new IllegalArgumentException();
                List<Grant> grants = new ArrayList<>();
                if ("ALLOW".equals(route.path("decision").asText())) {
                    for (JsonNode grant : route.path("grants")) {
                        String actor = grant.path("actor").asText();
                        if (!Set.of("customer", "staff", "anonymous").contains(actor)) throw new IllegalArgumentException();
                        grants.add(new Grant(actor, strings(grant.path("roles")), strings(grant.path("scopes"))));
                    }
                }
                loaded.put(key, List.copyOf(grants));
            }
            routes = Map.copyOf(loaded);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load explicit phase3-v2 route policy");
        }
    }

    private static Set<String> strings(JsonNode values) {
        if (!values.isArray()) throw new IllegalArgumentException();
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException();
            result.add(value.asText());
        }
        return Set.copyOf(result);
    }

    public boolean permite(VerifiedPrincipal principal, String routeKey) {
        if (principal == null || routeKey == null) return false;
        return routes.getOrDefault(routeKey, List.of()).stream().anyMatch(grant ->
                grant.actor.equals(principal.principalType())
                        && principal.permissions().containsAll(grant.scopes)
                        && (grant.roles.isEmpty() || grant.roles.stream().anyMatch(principal.permissions()::contains)));
    }
}
