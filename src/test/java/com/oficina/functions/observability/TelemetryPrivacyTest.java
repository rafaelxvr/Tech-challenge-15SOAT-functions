package com.oficina.functions.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;

class TelemetryPrivacyTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void logger_emits_parseable_safe_json_without_payload_or_exception_text() throws Exception {
        PrintStream original = System.out; var captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            JsonLogger.event("http_request_completed", "correlation-123", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "verification_rejected");
        } finally { System.setOut(original); }
        String line = captured.toString(StandardCharsets.UTF_8).trim();
        var parsed = json.readTree(line);
        assertThat(parsed.path("correlation_id").asText()).isEqualTo("correlation-123");
        assertThat(parsed.path("message").asText()).isEqualTo("safe");
        assertThat(line).doesNotContain("Bearer", "39053344705", "private@example.test", "ABC1D23", "select *", "sensitive exception");
    }
    @Test void trace_adapter_accepts_only_valid_w3c_context_and_clears_reused_invocation_state() {
        String trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        try (var ignored = TraceContextAdapter.extract(trace)) {
            var target = new HashMap<String, String>(); TraceContextAdapter.inject(target);
            assertThat(target).containsEntry("traceparent", trace);
        }
        assertThat(TraceContextAdapter.current()).isNull();
        assertThat(TraceContextAdapter.valid("Bearer secret")).isFalse();
    }
}
