package com.oficina.functions.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Plain notification use case. A thrown exception deliberately leaves the SQS record retryable. */
public final class NotificarStatus {
    private static final Set<String> STATUSES = Set.of("RECEBIDA", "EM_DIAGNOSTICO", "AGUARDANDO_APROVACAO",
            "EM_EXECUCAO", "FINALIZADA", "ENTREGUE");
    private final DestinatarioLookup destinatarios;
    private final DeliveryLedger ledger;
    private final StatusEmailSender email;
    private final Clock clock;

    public NotificarStatus(DestinatarioLookup destinatarios, DeliveryLedger ledger, StatusEmailSender email, Clock clock) {
        this.destinatarios = Objects.requireNonNull(destinatarios);
        this.ledger = Objects.requireNonNull(ledger);
        this.email = Objects.requireNonNull(email);
        this.clock = Objects.requireNonNull(clock);
    }

    public void executar(StatusOrdemServicoRegistrado event, String messageGroupId) {
        validar(event, messageGroupId);
        Instant now = clock.instant();
        UUID owner = UUID.randomUUID();
        ClaimResult claim = ledger.claim(event.eventId(), event.ordemId(), owner, now);
        if (claim == ClaimResult.TERMINAL) return;
        if (claim == ClaimResult.BUSY) throw new IllegalStateException("Notification delivery lease is active");

        if (event.sequencia() <= ledger.completedSequence(event.ordemId())) {
            ledger.complete(event.eventId(), event.ordemId(), event.sequencia(), owner, "SUPERSEDED", null, now);
            return;
        }
        if (!event.ocorridoEm().plus(Duration.ofHours(24)).isAfter(now)) {
            ledger.complete(event.eventId(), event.ordemId(), event.sequencia(), owner, "EXPIRED", null, now);
            return;
        }
        Optional<Destinatario> recipient = destinatarios.porOrdem(event.ordemId());
        if (recipient.isEmpty() || !eligible(recipient.get(), event)) {
            ledger.complete(event.eventId(), event.ordemId(), event.sequencia(), owner, "SUPPRESSED", null, now);
            return;
        }
        String messageId = email.enviar(recipient.get(), event);
        // SES and DynamoDB cannot share a transaction. A crash here can produce a duplicate after lease expiry.
        ledger.complete(event.eventId(), event.ordemId(), event.sequencia(), owner, "SES_ACCEPTED", messageId, now);
    }

    private static boolean eligible(Destinatario recipient, StatusOrdemServicoRegistrado event) {
        return recipient.ativo() && recipient.ordemId().equals(event.ordemId()) && recipient.numero() == event.numero()
                && recipient.clienteId().equals(event.clienteId()) && recipient.versaoIdentidade() == event.versaoIdentidadeCliente()
                && recipient.email() != null && recipient.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    }
    private static void validar(StatusOrdemServicoRegistrado e, String group) {
        if (e == null || e.schemaVersion() != 1 || !"StatusOrdemServicoRegistrado".equals(e.eventType())
                || e.eventId() == null || e.ordemId() == null || e.clienteId() == null || e.correlationId() == null
                || e.numero() <= 0 || e.versaoIdentidadeCliente() <= 0 || e.sequencia() <= 0 || e.ocorridoEm() == null
                || !STATUSES.contains(e.statusNovo()) || !e.ordemId().toString().equals(group))
            throw new IllegalArgumentException("Unsupported notification event");
    }
}
