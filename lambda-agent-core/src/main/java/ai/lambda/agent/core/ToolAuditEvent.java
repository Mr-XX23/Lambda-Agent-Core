package ai.lambda.agent.core;

import java.time.Instant;
import java.util.Set;

public record ToolAuditEvent(Instant timestamp, String sessionId, String toolName,
                             String toolCallId, String action, String reason,
                             Set<ToolCapability> capabilities) {
    public ToolAuditEvent {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
