package com.oficina.functions.notification;

import org.junit.jupiter.api.Test;
import java.time.*; import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificarStatusTest {
    private final Instant now = Instant.parse("2026-09-15T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Destinatario recipient = new Destinatario(UUID.fromString("00000000-0000-0000-0000-000000000201"), 1001,
            UUID.fromString("00000000-0000-0000-0000-000000000301"), true, "customer@example.test", 1);

    @Test void deliversOnlyOnceAfterTerminalCompletion() {
        InMemoryLedger ledger = new InMemoryLedger(); StatusEmailSender sender = mock(StatusEmailSender.class); when(sender.enviar(any(), any())).thenReturn("ses-1");
        NotificarStatus useCase = useCase(ledger, Optional.of(recipient), sender); StatusOrdemServicoRegistrado event = event(1, now);
        useCase.executar(event, event.ordemId().toString()); useCase.executar(event, event.ordemId().toString());
        verify(sender, times(1)).enviar(recipient, event); assertThat(ledger.outcomes).containsEntry(event.eventId(), "SES_ACCEPTED");
    }
    @Test void suppressesChangedContactOrDeactivatedRecipient() {
        InMemoryLedger ledger = new InMemoryLedger(); StatusEmailSender sender = mock(StatusEmailSender.class);
        Destinatario changed = new Destinatario(recipient.ordemId(), recipient.numero(), recipient.clienteId(), false, recipient.email(), 2);
        NotificarStatus useCase = useCase(ledger, Optional.of(changed), sender); StatusOrdemServicoRegistrado event = event(1, now);
        useCase.executar(event, event.ordemId().toString()); verifyNoInteractions(sender); assertThat(ledger.outcomes).containsEntry(event.eventId(), "SUPPRESSED");
    }
    @Test void expiresOldEventsWithoutSending() {
        InMemoryLedger ledger = new InMemoryLedger(); StatusEmailSender sender = mock(StatusEmailSender.class); StatusOrdemServicoRegistrado event = event(1, now.minus(Duration.ofHours(24)));
        useCase(ledger, Optional.of(recipient), sender).executar(event, event.ordemId().toString());
        verifyNoInteractions(sender); assertThat(ledger.outcomes).containsEntry(event.eventId(), "EXPIRED");
    }
    @Test void suppressesReplayAfterLaterSequenceCompleted() {
        InMemoryLedger ledger = new InMemoryLedger(); ledger.cursor = 2; StatusEmailSender sender = mock(StatusEmailSender.class); StatusOrdemServicoRegistrado event = event(1, now);
        useCase(ledger, Optional.of(recipient), sender).executar(event, event.ordemId().toString());
        verifyNoInteractions(sender); assertThat(ledger.outcomes).containsEntry(event.eventId(), "SUPERSEDED");
    }
    @Test void activeLeaseAndDependenciesAreRetryable() {
        InMemoryLedger ledger = new InMemoryLedger(); ledger.nextClaim = ClaimResult.BUSY;
        StatusOrdemServicoRegistrado event = event(1, now);
        assertThatThrownBy(() -> useCase(ledger, Optional.of(recipient), mock(StatusEmailSender.class)).executar(event, event.ordemId().toString())).isInstanceOf(IllegalStateException.class);
        InMemoryLedger acquired = new InMemoryLedger(); StatusEmailSender failure = (d, e) -> { throw new IllegalStateException("ses unavailable"); };
        assertThatThrownBy(() -> useCase(acquired, Optional.of(recipient), failure).executar(event(2, now), event.ordemId().toString())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new NotificarStatus(id -> { throw new IllegalStateException("lookup unavailable"); }, new InMemoryLedger(), mock(StatusEmailSender.class), clock)
                .executar(event(3, now), recipient.ordemId().toString())).isInstanceOf(IllegalStateException.class);
    }
    @Test void documentsResidualDuplicateWindowAfterSesAcceptanceBeforeCompletion() {
        InMemoryLedger ledger = new InMemoryLedger(); ledger.failNextCompletion = true; StatusEmailSender sender = mock(StatusEmailSender.class); when(sender.enviar(any(), any())).thenReturn("ses-1");
        StatusOrdemServicoRegistrado event = event(1, now); NotificarStatus useCase = useCase(ledger, Optional.of(recipient), sender);
        assertThatThrownBy(() -> useCase.executar(event, event.ordemId().toString())).isInstanceOf(IllegalStateException.class);
        useCase.executar(event, event.ordemId().toString());
        verify(sender, times(2)).enviar(recipient, event); // SES and DynamoDB cannot atomically guarantee exactly-once mail.
    }
    @Test void rejectsMalformedSchemaAndWrongFifoGroupBeforeLookup() {
        DestinatarioLookup lookup = mock(DestinatarioLookup.class); StatusOrdemServicoRegistrado invalid = new StatusOrdemServicoRegistrado(UUID.randomUUID(), "wrong", 2, recipient.ordemId(), 0, recipient.clienteId(), 0, 0, null, "NO", now, UUID.randomUUID(), null);
        assertThatThrownBy(() -> new NotificarStatus(lookup, new InMemoryLedger(), mock(StatusEmailSender.class), clock).executar(invalid, "wrong")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lookup);
    }
    private NotificarStatus useCase(InMemoryLedger ledger, Optional<Destinatario> result, StatusEmailSender sender) { return new NotificarStatus(id -> result, ledger, sender, clock); }
    private StatusOrdemServicoRegistrado event(long sequence, Instant occurred) { return new StatusOrdemServicoRegistrado(UUID.randomUUID(), "StatusOrdemServicoRegistrado", 1, recipient.ordemId(), recipient.numero(), recipient.clienteId(), 1, sequence, null, "RECEBIDA", occurred, UUID.randomUUID(), null); }
    private static final class InMemoryLedger implements DeliveryLedger {
        private final Map<UUID, String> outcomes = new HashMap<>(); private long cursor; private ClaimResult nextClaim = ClaimResult.ACQUIRED; private boolean failNextCompletion;
        @Override public ClaimResult claim(UUID eventId, UUID orderId, UUID owner, Instant now) { return outcomes.containsKey(eventId) ? ClaimResult.TERMINAL : nextClaim; }
        @Override public long completedSequence(UUID orderId) { return cursor; }
        @Override public void complete(UUID eventId, UUID orderId, long sequence, UUID owner, String outcome, String messageId, Instant now) { if (failNextCompletion) { failNextCompletion = false; throw new IllegalStateException("ledger unavailable after SES"); } outcomes.put(eventId, outcome); cursor = Math.max(cursor, sequence); }
    }
}
