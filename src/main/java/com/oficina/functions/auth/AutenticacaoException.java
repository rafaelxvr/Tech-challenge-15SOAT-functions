package com.oficina.functions.auth;

/** Public boundary error: no dependency message or cause is attached. */
public final class AutenticacaoException extends RuntimeException {
    private final int status;
    private final String codigo;

    public AutenticacaoException(int status, String codigo) {
        super(codigo);
        this.status = status;
        this.codigo = codigo;
    }
    public int status() { return status; }
    public String codigo() { return codigo; }

    static AutenticacaoException invalida() {
        return new AutenticacaoException(401, "DESAFIO_INVALIDO");
    }
    static AutenticacaoException indisponivel() {
        return new AutenticacaoException(503, "SERVICO_INDISPONIVEL");
    }
}
