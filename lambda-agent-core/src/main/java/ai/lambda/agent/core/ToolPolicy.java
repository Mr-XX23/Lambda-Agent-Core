package ai.lambda.agent.core;

import java.time.Duration;
import java.util.Objects;

public record ToolPolicy(boolean requiresApproval, Duration timeout) {
    public ToolPolicy {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static ToolPolicy unrestricted() {
        return new ToolPolicy(false, Duration.ofMinutes(1));
    }
}
