package com.oficina.functions.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class CriarDesafio {
    private final ClienteLookup lookup;
    private final DesafioStore store;
    private final EnviarCodigo email;
    private final GeradorCodigo gerador;
    private final OtpHasher hasher;
    private final Clock clock;

    public CriarDesafio(ClienteLookup lookup, DesafioStore store, EnviarCodigo email,
                        GeradorCodigo gerador, OtpHasher hasher, Clock clock) {
        this.lookup = lookup;
        this.store = store;
        this.email = email;
        this.gerador = gerador;
        this.hasher = hasher;
        this.clock = clock;
    }

    public EmissaoDesafio executar(String valorCpf, String origem) {
        String cpf = Cpf.normalizar(valorCpf);
        if (origem == null || origem.isBlank()) {
            throw new AutenticacaoException(400, "ORIGEM_INVALIDA");
        }
        Desafio desafio;
        ClienteSnapshot cliente;
        String codigo;
        boolean emitido;
        try {
            Instant agora = clock.instant();
            cliente = lookup.porCpf(cpf).filter(c -> c.ativo() && c.id() != null
                    && c.versaoIdentidade() > 0 && cpf.equals(c.cpf())
                    && c.email() != null && !c.email().isBlank()).orElse(null);
            codigo = gerador.gerar();
            byte[] salt = hasher.novoSalt();
            desafio = new Desafio(UUID.randomUUID(), identificadorHash("cpf", cpf),
                    cliente == null ? null : cliente.id(),
                    cliente == null ? 0 : cliente.versaoIdentidade(), salt,
                    hasher.hash(codigo, salt), agora.plusSeconds(300));
            emitido = store.emitir(desafio, identificadorHash("origem", origem), agora);
        } catch (RuntimeException ex) {
            throw AutenticacaoException.indisponivel();
        }
        if (!emitido) throw new AutenticacaoException(429, "LIMITE_EXCEDIDO");
        if (cliente != null) {
            try {
                email.enviar(cliente.email(), codigo);
            } catch (RuntimeException ex) {
                try {
                    store.invalidar(desafio.id());
                } catch (RuntimeException ignored) {
                    // The caller still receives a safe failure; no dependency payload escapes.
                }
                throw AutenticacaoException.indisponivel();
            }
        }
        return new EmissaoDesafio(desafio.id(), 300);
    }

    private static String identificadorHash(String tipo, String valor) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((tipo + ":" + valor).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Identifier hashing unavailable");
        }
    }
}
