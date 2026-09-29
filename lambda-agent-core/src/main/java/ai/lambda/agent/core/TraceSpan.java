package ai.lambda.agent.core;

import java.time.Instant;
import java.util.Map;

public record TraceSpan(String traceId, String spanId, String parentSpanId, String name,
                        Instant startedAt, long durationMillis, Map<String, Object> attributes) {
    public TraceSpan {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
