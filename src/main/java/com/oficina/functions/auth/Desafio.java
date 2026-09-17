package com.oficina.functions.auth;

import java.time.Instant;
import java.util.UUID;

public record Desafio(UUID id, String cpfHash, UUID clienteId, long versaoIdentidade,
                      byte[] salt, byte[] hash, Instant expiraEm) {
    public Desafio {
        salt = salt.clone();
        hash = hash.clone();
    }
    @Override public byte[] salt() { return salt.clone(); }
    @Override public byte[] hash() { return hash.clone(); }
    @Override public String toString() { return "Desafio[redacted]"; }
}
