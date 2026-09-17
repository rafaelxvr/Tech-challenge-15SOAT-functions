package com.oficina.functions.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public final class VerificarDesafio {
    private final ClienteLookup lookup;
    private final DesafioStore store;
    private final TokenSigner signer;
    private final Clock clock;

    public VerificarDesafio(ClienteLookup lookup, DesafioStore store, TokenSigner signer, Clock clock) {
        this.lookup = lookup;
        this.store = store;
        this.signer = signer;
        this.clock = clock;
    }

    public TokenResposta executar(UUID id, String codigo) {
        if (id == null) throw AutenticacaoException.invalida();
        Desafio desafio;
        try {
            // Malformed codes still spend an attempt in the atomic store.
            desafio = store.verificarEConsumir(id, codigo, clock.instant()).orElse(null);
        } catch (RuntimeException ex) {
            throw AutenticacaoException.indisponivel();
        }
        if (desafio == null || desafio.clienteId() == null) throw AutenticacaoException.invalida();

        ClienteSnapshot cliente;
        try {
            cliente = lookup.porId(desafio.clienteId()).orElse(null);
        } catch (RuntimeException ex) {
            throw AutenticacaoException.indisponivel();
        }
        if (cliente == null || !cliente.ativo() || !desafio.clienteId().equals(cliente.id())
                || cliente.versaoIdentidade() <= 0
                || cliente.versaoIdentidade() != desafio.versaoIdentidade()) {
            throw AutenticacaoException.invalida();
        }
        try {
            Instant agora = clock.instant();
            String token = signer.emitir(cliente, agora);
            if (token == null || token.isBlank()) throw AutenticacaoException.indisponivel();
            return new TokenResposta(token, "Bearer", 900);
        } catch (RuntimeException ex) {
            throw AutenticacaoException.indisponivel();
        }
    }
}
