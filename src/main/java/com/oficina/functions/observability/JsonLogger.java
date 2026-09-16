package com.oficina.functions.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Small Lambda JSON logger. Callers supply only allowlisted diagnostic fields, never payloads or exceptions. */
public final class JsonLogger {
    private static final Set<String> EVENTS = Set.of("lambda_cold_start", "http_request_completed", "notification_completed", "notification_failed");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.systemUTC();
    private JsonLogger() { }
    public static void event(String eventName, String correlationId, String traceparent, String diagnosticId) {
        if (!EVENTS.contains(eventName)) throw new IllegalArgumentException("Unsupported telemetry event");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", Instant.now(CLOCK).toString());
        line.put("service", "oficina-functions");
        line.put("environment", environment());
        line.put("event_name", eventName);
        line.put("level", "INFO");
        line.put("message", "safe");
        if (correlationId != null) line.put("correlation_id", correlationId);
        if (traceparent != null) line.put("traceparent", traceparent);
        if (diagnosticId != null) line.put("diagnostic_id", diagnosticId);
        try { System.out.println(JSON.writeValueAsString(line)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Safe telemetry serialization unavailable"); }
    }
    private static String environment() {
        String value = System.getenv("OFICINA_ENVIRONMENT");
        return "staging".equals(value) || "production".equals(value) ? value : "local";
    }
}
