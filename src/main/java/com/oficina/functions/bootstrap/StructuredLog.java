package com.oficina.functions.bootstrap;

/** R1 can collect these JSON records. Request bodies, email addresses, OTPs and JWTs are never arguments. */
public final class StructuredLog {
    private StructuredLog() { }
    public static void coldStart(String component) {
        System.out.println("{\"event\":\"lambda_cold_start\",\"component\":\"" + component + "\"}");
    }
}
