package com.oficina.functions.notification;

import java.time.Instant;
import java.util.UUID;

/** Durable delivery state. TTL assists cleanup only; callers always enforce terminal state and leases. */
public interface DeliveryLedger {
    ClaimResult claim(UUID eventId, UUID ordemId, UUID owner, Instant now);
    long completedSequence(UUID ordemId);
    void complete(UUID eventId, UUID ordemId, long sequencia, UUID owner,
                  String outcome, String messageId, Instant now);
}
