package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.oficina.functions.auth.AutenticacaoException;
import com.oficina.functions.auth.Authorizer;
import java.util.Map;

/** HTTP API simple-response authorizer: invalid credentials are 401, denied grants are 403. */
public final class AuthorizerHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    private final Authorizer authorizer;
    public AuthorizerHandler() { this(AuthorizerFactoryHolder.authorizer()); }
    public AuthorizerHandler(Authorizer authorizer) { this.authorizer = authorizer; }

    @Override public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String token = token(event);
        String route = text(event, "routeKey");
        if (token == null || route == null) return Map.of("errorMessage", "Unauthorized");
        try {
            return Map.of("isAuthorized", authorizer.autorizar(token, route));
        } catch (AutenticacaoException exception) {
            return Map.of("errorMessage", "Unauthorized");
        }
    }

    @SuppressWarnings("unchecked")
    private static String token(Map<String, Object> event) {
        if (event == null) return null;
        Object identity = event.get("identitySource");
        if (identity instanceof java.util.List<?> values && !values.isEmpty() && values.get(0) instanceof String value) return value;
        Object headers = event.get("headers");
        if (headers instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (entry.getKey() instanceof String name && "authorization".equalsIgnoreCase(name)
                        && entry.getValue() instanceof String value) return value;
        }
        return null;
    }
    private static String text(Map<String, Object> event, String name) {
        return event != null && event.get(name) instanceof String value && !value.isBlank() ? value : null;
    }
}
