package ai.lambda.agent.core;

import java.time.Instant;
import java.util.Map;

public record TraceEvent(
        Instant timestamp,
        String runId,
        String sessionId,
        TraceEventType type,
        String name,
        long durationMillis,
        Map<String, Object> attributes) {
    public TraceEvent {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
    }
}
