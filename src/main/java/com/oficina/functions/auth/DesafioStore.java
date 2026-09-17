package com.oficina.functions.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** All state decisions are atomic; TTL is cleanup, never the validity check. */
public interface DesafioStore {
    /**
     * Atomically enforce a 60-second CPF cooldown and at most 10 successful emissions
     * per source/300-second epoch bucket. On success persist the new current challenge,
     * invalidating its predecessor, including for dummy customers. False means throttled.
     * A rejected emission must not replace the current challenge.
     */
    boolean emitir(Desafio desafio, String origemHash, Instant agora);

    /**
     * Check current issuance, expiry (now strictly before expiry), five-attempt budget
     * and unconsumed state. A wrong code atomically spends one attempt; a correct code
     * atomically consumes the challenge. Only the committed winner returns a snapshot.
     * PBKDF2 verification belongs inside this atomic store operation.
     */
    Optional<Desafio> verificarEConsumir(UUID id, String codigo, Instant agora);

    /** Idempotently make the challenge unusable without clearing its CPF cooldown. */
    void invalidar(UUID id);
}
