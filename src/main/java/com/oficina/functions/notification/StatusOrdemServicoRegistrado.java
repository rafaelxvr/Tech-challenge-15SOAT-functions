package com.oficina.functions.notification;

import java.time.Instant;
import java.util.UUID;

/** Immutable B2 status-event wire contract. Contact data and credentials never belong here. */
public record StatusOrdemServicoRegistrado(UUID eventId, String eventType, int schemaVersion,
                                           UUID ordemId, long numero, UUID clienteId,
                                           long versaoIdentidadeCliente, long sequencia,
                                           String statusAnterior, String statusNovo,
                                           Instant ocorridoEm, UUID correlationId,
                                           String traceparent) { }
