package com.oficina.functions.auth;

public record TokenResposta(String accessToken, String tokenType, int expiresIn) {
    @Override public String toString() { return "TokenResposta[redacted]"; }
}
