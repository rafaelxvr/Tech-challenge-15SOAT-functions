package com.oficina.functions.notification;

import java.util.Optional;
import java.util.UUID;

public interface DestinatarioLookup {
    Optional<Destinatario> porOrdem(UUID ordemId);
}
