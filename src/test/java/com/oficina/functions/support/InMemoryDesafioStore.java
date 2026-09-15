package com.oficina.functions.support;

import com.oficina.functions.auth.Desafio;
import com.oficina.functions.auth.DesafioStore;
import com.oficina.functions.auth.OtpHasher;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Unit-test fake only. Production atomic state is supplied by the F2 adapter. */
public final class InMemoryDesafioStore implements DesafioStore {
    private final OtpHasher hasher;
    private final Map<UUID, State> challenges = new HashMap<>();
    private final Map<String, UUID> current = new HashMap<>();
    private final Map<String, Instant> lastIssue = new HashMap<>();
    private final Map<String, Integer> sources = new HashMap<>();

    public InMemoryDesafioStore(OtpHasher hasher) { this.hasher = hasher; }

    @Override public synchronized boolean emitir(Desafio desafio, String origemHash, Instant agora) {
        Instant last = lastIssue.get(desafio.cpfHash());
        String bucket = origemHash + ":" + Math.floorDiv(agora.getEpochSecond(), 300);
        if ((last != null && agora.isBefore(last.plusSeconds(60)))
                || sources.getOrDefault(bucket, 0) >= 10) return false;
        UUID prior = current.put(desafio.cpfHash(), desafio.id());
        if (prior != null) invalidar(prior);
        challenges.put(desafio.id(), new State(desafio));
        lastIssue.put(desafio.cpfHash(), agora);
        sources.merge(bucket, 1, Integer::sum);
        return true;
    }

    @Override public synchronized Optional<Desafio> verificarEConsumir(UUID id, String codigo, Instant agora) {
        State state = challenges.get(id);
        if (state == null || state.consumed || state.attempts >= 5
                || !agora.isBefore(state.desafio.expiraEm())
                || !id.equals(current.get(state.desafio.cpfHash()))) return Optional.empty();
        state.attempts++;
        if (!hasher.verificar(codigo, state.desafio.salt(), state.desafio.hash())) return Optional.empty();
        state.consumed = true;
        return Optional.of(state.desafio);
    }

    @Override public synchronized void invalidar(UUID id) {
        State state = challenges.get(id);
        if (state != null) state.consumed = true;
    }

    public synchronized Optional<Desafio> snapshot(UUID id) {
        return Optional.ofNullable(challenges.get(id)).map(s -> s.desafio);
    }
    public synchronized int size() { return challenges.size(); }

    private static final class State {
        private final Desafio desafio;
        private int attempts;
        private boolean consumed;
        private State(Desafio desafio) { this.desafio = desafio; }
    }
}
