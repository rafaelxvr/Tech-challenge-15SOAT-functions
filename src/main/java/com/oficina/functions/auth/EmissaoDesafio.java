package com.oficina.functions.auth;

import java.util.UUID;

public record EmissaoDesafio(UUID desafioId, int expiraEmSegundos) {}
