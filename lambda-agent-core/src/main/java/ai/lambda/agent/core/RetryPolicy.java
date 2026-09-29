package ai.lambda.agent.core;

import java.time.Duration;
import java.util.Objects;

public record RetryPolicy(int maxAttempts, Duration initialBackoff, double backoffMultiplier) {
    public RetryPolicy {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        if (initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must not be negative");
        }
        if (backoffMultiplier < 1.0 || Double.isNaN(backoffMultiplier)
                || Double.isInfinite(backoffMultiplier)) {
            throw new IllegalArgumentException("backoffMultiplier must be finite and at least 1");
        }
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0);
    }

    public static RetryPolicy exponential(int maxAttempts, Duration initialBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, 2.0);
    }

    public Duration delayBeforeAttempt(int attemptNumber) {
        if (attemptNumber <= 1 || initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        double multiplier = Math.pow(backoffMultiplier, attemptNumber - 2L);
        long delayMillis = Math.min(
                Duration.ofMillis(Long.MAX_VALUE).toMillis(),
                Math.round(initialBackoff.toMillis() * multiplier));
        return Duration.ofMillis(delayMillis);
    }
}
