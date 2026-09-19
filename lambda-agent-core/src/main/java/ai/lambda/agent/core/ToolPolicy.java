package ai.lambda.agent.core;

import java.time.Duration;
import java.util.Objects;

public record ToolPolicy(boolean requiresApproval, Duration timeout, int maxResultLength) {
    public ToolPolicy {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxResultLength <= 0) {
            throw new IllegalArgumentException("maxResultLength must be positive");
        }
    }

    public ToolPolicy(boolean requiresApproval, Duration timeout) {
        this(requiresApproval, timeout, 128 * 1024);
    }

    public static ToolPolicy unrestricted() {
        return new ToolPolicy(false, Duration.ofMinutes(1), 128 * 1024);
    }
}
