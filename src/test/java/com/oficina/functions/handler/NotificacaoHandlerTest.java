package com.oficina.functions.handler;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.oficina.functions.notification.*;
import org.junit.jupiter.api.Test;
import java.time.*; import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NotificacaoHandlerTest {
    @Test void acceptsExactlyOneValidFifoRecord() {
        NotificarStatus notification = new NotificarStatus(id -> Optional.empty(), new TerminalLedger(), (d,e) -> "unused", Clock.systemUTC());
        NotificacaoHandler handler = new NotificacaoHandler(notification); SQSEvent event = sqs(validBody());
        assertThat(handler.handleRequest(event, null)).isNull();
    }
    @Test void rejectsMalformedOrBatchRecordsSoLambdaRetries() {
        NotificacaoHandler handler = new NotificacaoHandler(new NotificarStatus(id -> Optional.empty(), new TerminalLedger(), (d,e) -> "unused", Clock.systemUTC()));
        assertThatThrownBy(() -> handler.handleRequest(sqs("{}"), null)).isInstanceOf(IllegalArgumentException.class);
        SQSEvent batch = sqs(validBody()); batch.getRecords().add(new SQSEvent.SQSMessage()); assertThatThrownBy(() -> handler.handleRequest(batch, null)).isInstanceOf(IllegalArgumentException.class);
    }
    private static SQSEvent sqs(String body) { SQSEvent.SQSMessage message = new SQSEvent.SQSMessage(); message.setBody(body); message.setAttributes(Map.of("MessageGroupId", "00000000-0000-0000-0000-000000000201")); SQSEvent event = new SQSEvent(); event.setRecords(new ArrayList<>(List.of(message))); return event; }
    private static String validBody() { return "{\"eventId\":\"00000000-0000-0000-0000-000000000101\",\"eventType\":\"StatusOrdemServicoRegistrado\",\"schemaVersion\":1,\"ordemId\":\"00000000-0000-0000-0000-000000000201\",\"numero\":1001,\"clienteId\":\"00000000-0000-0000-0000-000000000301\",\"versaoIdentidadeCliente\":1,\"sequencia\":1,\"statusAnterior\":null,\"statusNovo\":\"RECEBIDA\",\"ocorridoEm\":\"2026-09-15T12:00:00Z\",\"correlationId\":\"00000000-0000-0000-0000-000000000401\",\"traceparent\":null}"; }
    private static final class TerminalLedger implements DeliveryLedger { public ClaimResult claim(UUID a, UUID b, UUID c, Instant d) { return ClaimResult.TERMINAL; } public long completedSequence(UUID id) { return 0; } public void complete(UUID a, UUID b, long c, UUID d, String e, String f, Instant g) { } }
}
