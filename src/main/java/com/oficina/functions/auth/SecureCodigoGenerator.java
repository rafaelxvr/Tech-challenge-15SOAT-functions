package com.oficina.functions.auth;

import java.security.SecureRandom;
import java.util.Locale;

public final class SecureCodigoGenerator implements GeradorCodigo {
    private final SecureRandom random = new SecureRandom();

    @Override public String gerar() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }
}
