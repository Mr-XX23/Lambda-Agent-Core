package ai.lambda.agent.core;

import java.util.Objects;

public record PermissionDecision(boolean allowed, String reason) {
    public PermissionDecision {
        reason = Objects.requireNonNullElse(reason, allowed ? "allowed" : "denied");
    }

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(true, reason);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, reason);
    }
}
