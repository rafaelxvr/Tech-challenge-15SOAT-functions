package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oficina.functions.bootstrap.FunctionFactory;
import com.oficina.functions.notification.NotificarStatus;
import com.oficina.functions.notification.StatusOrdemServicoRegistrado;
import com.oficina.functions.observability.JsonLogger;
import com.oficina.functions.observability.TraceContextAdapter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** FIFO source mapping uses batch size one. Any retryable error fails this invocation. */
public final class NotificacaoHandler implements RequestHandler<SQSEvent, Void> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final NotificarStatus notificacao;

    public NotificacaoHandler() { this(FunctionFactory.notificationFromEnvironment()); }
    public NotificacaoHandler(NotificarStatus notificacao) { this.notificacao = notificacao; }
    @Override public Void handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null || event.getRecords().size() != 1) throw new IllegalArgumentException("Expected one FIFO record");
        SQSEvent.SQSMessage message = event.getRecords().get(0);
        if (message.getBody() == null || message.getBody().getBytes(StandardCharsets.UTF_8).length > 8 * 1024) throw new IllegalArgumentException("Unsupported notification payload");
        StatusOrdemServicoRegistrado parsed;
        try {
            JsonNode node = JSON.readTree(message.getBody());
            parsed = event(node);
        } catch (IllegalArgumentException exception) {
            JsonLogger.event("notification_failed", null, null, "invalid_event");
            throw exception;
        } catch (Exception exception) {
            JsonLogger.event("notification_failed", null, null, "processing_failure");
            throw new IllegalStateException("Notification processing failed");
        }
        try (var trace = TraceContextAdapter.extract(parsed.traceparent())) {
            String correlation = parsed.correlationId().toString();
            try {
                notificacao.executar(parsed, message.getAttributes() == null ? null : message.getAttributes().get("MessageGroupId"));
                JsonLogger.event("notification_completed", correlation, TraceContextAdapter.current(), "ses_accepted_or_suppressed");
            } catch (IllegalArgumentException exception) {
                JsonLogger.event("notification_failed", correlation, TraceContextAdapter.current(), "invalid_event");
                throw exception;
            } catch (Exception exception) {
                JsonLogger.event("notification_failed", correlation, TraceContextAdapter.current(), "processing_failure");
                throw new IllegalStateException("Notification processing failed");
            }
        }
        return null;
    }
    private static StatusOrdemServicoRegistrado event(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("Unsupported notification payload");
        return new StatusOrdemServicoRegistrado(uuid(n, "eventId"), text(n, "eventType"), integer(n, "schemaVersion"), uuid(n, "ordemId"),
                number(n, "numero"), uuid(n, "clienteId"), number(n, "versaoIdentidadeCliente"), number(n, "sequencia"),
                nullableText(n, "statusAnterior"), text(n, "statusNovo"), Instant.parse(text(n, "ocorridoEm")), uuid(n, "correlationId"), nullableText(n, "traceparent"));
    }
    private static String text(JsonNode n, String field) { if (!n.path(field).isTextual()) throw new IllegalArgumentException("Unsupported notification payload"); return n.path(field).textValue(); }
    private static String nullableText(JsonNode n, String field) { return n.path(field).isNull() || n.path(field).isMissingNode() ? null : text(n, field); }
    private static UUID uuid(JsonNode n, String field) { try { return UUID.fromString(text(n, field)); } catch (RuntimeException e) { throw new IllegalArgumentException("Unsupported notification payload"); } }
    private static long number(JsonNode n, String field) { if (!n.path(field).canConvertToLong()) throw new IllegalArgumentException("Unsupported notification payload"); return n.path(field).longValue(); }
    private static int integer(JsonNode n, String field) { if (!n.path(field).canConvertToInt()) throw new IllegalArgumentException("Unsupported notification payload"); return n.path(field).intValue(); }
}
