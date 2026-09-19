package ai.lambda.agent.core;

import java.util.Objects;

public record EvaluationCase(String name, String sessionId, String input,
                             String expectedText, int expectedToolCalls) {
    public EvaluationCase {
        Objects.requireNonNull(name);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(input);
        if (expectedToolCalls < 0) throw new IllegalArgumentException("expectedToolCalls must not be negative");
    }
}
