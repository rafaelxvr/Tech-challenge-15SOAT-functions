package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.oficina.functions.notification.*;
import org.junit.jupiter.api.Test;
import java.time.*; import java.util.*;
import java.io.*; import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.functions.observability.TraceContextAdapter;
import static org.assertj.core.api.Assertions.*;

class NotificacaoHandlerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-15T12:05:00Z"), ZoneOffset.UTC);

    @Test void acceptsExactlyOneValidFifoRecord() {
        NotificarStatus notification = new NotificarStatus(id -> Optional.empty(), new TerminalLedger(), (d,e) -> "unused", TEST_CLOCK);
        NotificacaoHandler handler = new NotificacaoHandler(notification); SQSEvent event = sqs(validBody());
        assertThat(handler.handleRequest(event, null)).isNull();
    }
    @Test void rejectsMalformedOrBatchRecordsSoLambdaRetries() {
        NotificacaoHandler handler = new NotificacaoHandler(new NotificarStatus(id -> Optional.empty(), new TerminalLedger(), (d,e) -> "unused", TEST_CLOCK));
        assertThatThrownBy(() -> handler.handleRequest(sqs("{}"), null)).isInstanceOf(IllegalArgumentException.class);
        SQSEvent batch = sqs(validBody()); batch.getRecords().add(new SQSEvent.SQSMessage()); assertThatThrownBy(() -> handler.handleRequest(batch, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void post_parse_failure_keeps_safe_event_context_in_log_then_clears_reused_invocation_state() throws Exception {
        NotificarStatus failing = new NotificarStatus(id -> { throw new IllegalStateException("private@example.test Bearer secret"); }, new AcquiredLedger(), (d,e) -> "unused", TEST_CLOCK);
        String body = validBody().replace("\"traceparent\":null", "\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\"");
        PrintStream original = System.out; var captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertThatThrownBy(() -> new NotificacaoHandler(failing).handleRequest(sqs(body), null)).isInstanceOf(IllegalStateException.class);
        } finally { System.setOut(original); }
        String output = captured.toString(StandardCharsets.UTF_8).trim();
        var lines = output.lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(1);
        var json = new ObjectMapper().readTree(lines.get(0));
        assertThat(json.path("event_name").asText()).isEqualTo("notification_failed");
        assertThat(json.path("correlation_id").asText()).isEqualTo("00000000-0000-0000-0000-000000000401");
        assertThat(json.path("traceparent").asText()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(output).doesNotContain("private@example.test", "Bearer secret");
        assertThat(TraceContextAdapter.current()).isNull();
    }
    private static SQSEvent sqs(String body) { SQSEvent.SQSMessage message = new SQSEvent.SQSMessage(); message.setBody(body); message.setAttributes(Map.of("MessageGroupId", "00000000-0000-0000-0000-000000000201")); SQSEvent event = new SQSEvent(); event.setRecords(new ArrayList<>(List.of(message))); return event; }
    private static String validBody() { return "{\"eventId\":\"00000000-0000-0000-0000-000000000101\",\"eventType\":\"StatusOrdemServicoRegistrado\",\"schemaVersion\":1,\"ordemId\":\"00000000-0000-0000-0000-000000000201\",\"numero\":1001,\"clienteId\":\"00000000-0000-0000-0000-000000000301\",\"versaoIdentidadeCliente\":1,\"sequencia\":1,\"statusAnterior\":null,\"statusNovo\":\"RECEBIDA\",\"ocorridoEm\":\"2026-09-15T12:00:00Z\",\"correlationId\":\"00000000-0000-0000-0000-000000000401\",\"traceparent\":null}"; }
    private static final class TerminalLedger implements DeliveryLedger { public ClaimResult claim(UUID a, UUID b, UUID c, Instant d) { return ClaimResult.TERMINAL; } public long completedSequence(UUID id) { return 0; } public void complete(UUID a, UUID b, long c, UUID d, String e, String f, Instant g) { } }
    private static final class AcquiredLedger implements DeliveryLedger { public ClaimResult claim(UUID a, UUID b, UUID c, Instant d) { return ClaimResult.ACQUIRED; } public long completedSequence(UUID id) { return 0; } public void complete(UUID a, UUID b, long c, UUID d, String e, String f, Instant g) { } }
}
