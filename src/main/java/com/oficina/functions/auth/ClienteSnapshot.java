package com.oficina.functions.auth;

import java.util.UUID;

public record ClienteSnapshot(UUID id, String cpf, boolean ativo, String email, long versaoIdentidade) {
    @Override public String toString() { return "ClienteSnapshot[redacted]"; }
}
