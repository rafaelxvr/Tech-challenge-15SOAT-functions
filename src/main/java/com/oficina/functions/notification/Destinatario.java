package com.oficina.functions.notification;

import java.util.UUID;

/** Current contact snapshot, read from the APP-owned notification view. */
public record Destinatario(UUID ordemId, long numero, UUID clienteId, boolean ativo,
                           String email, long versaoIdentidade) { }
