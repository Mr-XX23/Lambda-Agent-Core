package ai.lambda.agent.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record ToolPolicy(boolean requiresApproval, Duration timeout, int maxResultLength,
                         Set<ToolCapability> capabilities) {
    public ToolPolicy {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxResultLength <= 0) {
            throw new IllegalArgumentException("maxResultLength must be positive");
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    public ToolPolicy(boolean requiresApproval, Duration timeout) {
        this(requiresApproval, timeout, 128 * 1024, Set.of());
    }

    public ToolPolicy(boolean requiresApproval, Duration timeout, int maxResultLength) {
        this(requiresApproval, timeout, maxResultLength, Set.of());
    }

    public static ToolPolicy unrestricted() {
        return new ToolPolicy(false, Duration.ofMinutes(1), 128 * 1024, Set.of());
    }
}
