package com.oficina.functions.auth;

import java.util.Optional;
import java.util.UUID;

public interface ClienteLookup {
    Optional<ClienteSnapshot> porCpf(String cpf);
    Optional<ClienteSnapshot> porId(UUID id);
}
