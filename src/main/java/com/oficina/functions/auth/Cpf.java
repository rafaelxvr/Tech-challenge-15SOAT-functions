package com.oficina.functions.auth;

public final class Cpf {
    private Cpf() {}

    public static String normalizar(String valor) {
        if (valor == null || !(valor.matches("[0-9]{11}")
                || valor.matches("[0-9]{3}\\.[0-9]{3}\\.[0-9]{3}-[0-9]{2}"))) {
            throw invalido();
        }
        String cpf = valor.replace(".", "").replace("-", "");
        if (cpf.chars().distinct().count() == 1) throw invalido();
        for (int tamanho = 9; tamanho <= 10; tamanho++) {
            int soma = 0;
            for (int i = 0; i < tamanho; i++) soma += (cpf.charAt(i) - '0') * (tamanho + 1 - i);
            int digito = 11 - soma % 11;
            if (digito >= 10) digito = 0;
            if (cpf.charAt(tamanho) - '0' != digito) throw invalido();
        }
        return cpf;
    }

    private static AutenticacaoException invalido() {
        return new AutenticacaoException(400, "CPF_INVALIDO");
    }
}
