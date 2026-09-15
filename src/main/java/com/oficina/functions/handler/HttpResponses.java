package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Produces the single public HTTP envelope.  It never serializes exception messages. */
public final class HttpResponses {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpResponses() { }

    public static APIGatewayV2HTTPResponse success(int status, String correlationId, Object data) {
        return response(status, correlationId, Map.of("data", data), null);
    }

    public static APIGatewayV2HTTPResponse failure(int status, String correlationId, String code) {
        return response(status, correlationId, Map.of("erro", Map.of("codigo", code)), status == 429 ? "60" : null);
    }

    private static APIGatewayV2HTTPResponse response(int status, String correlationId, Object body, String retryAfter) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Correlation-Id", correlationId);
        if (retryAfter != null) headers.put("Retry-After", retryAfter);
        try {
            return APIGatewayV2HTTPResponse.builder().withStatusCode(status).withHeaders(headers)
                    .withBody(JSON.writeValueAsString(body)).build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Response serialization unavailable");
        }
    }
}
