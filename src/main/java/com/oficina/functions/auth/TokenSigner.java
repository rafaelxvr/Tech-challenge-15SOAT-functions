package com.oficina.functions.auth;

import java.time.Instant;

public interface TokenSigner {
    /** Sign customer/access RS256 with current identity version, exp=iat+900; no refresh. */
    String emitir(ClienteSnapshot cliente, Instant agora);
}
