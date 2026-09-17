package com.oficina.functions.observability;

import java.util.Map;

/** W3C trace boundary: accepts only valid traceparent, never authentication headers or arbitrary baggage. */
public final class TraceContextAdapter {
    private static final String TRACE = "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}";
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TraceContextAdapter() { }
    public static Scope extract(String traceparent) {
        String value = valid(traceparent) ? traceparent : null; CURRENT.set(value); return new Scope();
    }
    public static String current() { return CURRENT.get(); }
    public static void inject(Map<String, String> target) { if (current() != null) target.put("traceparent", current()); }
    public static boolean valid(String value) {
        return value != null && value.matches(TRACE) && !value.substring(3, 35).equals("0".repeat(32))
                && !value.substring(36, 52).equals("0".repeat(16));
    }
    public static final class Scope implements AutoCloseable {
        private Scope() { }
        @Override public void close() { CURRENT.remove(); }
    }
}
