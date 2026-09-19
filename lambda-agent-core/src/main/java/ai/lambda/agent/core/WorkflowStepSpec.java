package ai.lambda.agent.core;

import java.time.Duration;
import java.util.Objects;

public record WorkflowStepSpec(String name, WorkflowStep step, RetryPolicy retryPolicy,
                               Duration timeout, WorkflowCondition condition) {
    public WorkflowStepSpec {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(step, "step must not be null");
        retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        condition = condition == null ? context -> true : condition;
    }

    public WorkflowStepSpec(String name, WorkflowStep step) {
        this(name, step, RetryPolicy.none(), Duration.ofMinutes(5), context -> true);
    }
}
