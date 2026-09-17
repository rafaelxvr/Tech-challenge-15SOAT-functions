package com.oficina.functions.bootstrap;

import com.oficina.functions.observability.JsonLogger;

/** R1 can collect these JSON records. Request bodies, email addresses, OTPs and JWTs are never arguments. */
public final class StructuredLog {
    private StructuredLog() { }
    public static void coldStart(String component) {
        // component is a code-owned, bounded diagnostic identifier; no environment/payload content is accepted.
        JsonLogger.event("lambda_cold_start", null, null, component);
    }
}
