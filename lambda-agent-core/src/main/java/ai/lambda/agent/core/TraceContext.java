package ai.lambda.agent.core;

import java.util.UUID;

public final class TraceContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private TraceContext() {}

    public static String currentTraceId() {
        String value = CURRENT.get();
        return value == null ? "" : value;
    }

    public static void activate(String traceId) {
        CURRENT.set(traceId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Scope open(String traceId) {
        String previous = CURRENT.get();
        CURRENT.set(traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
