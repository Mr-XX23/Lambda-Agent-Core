package ai.lambda.agent.core;

public record EvaluationResult(String name, boolean passed, String actualText,
                               int actualToolCalls, String failure) {
}
