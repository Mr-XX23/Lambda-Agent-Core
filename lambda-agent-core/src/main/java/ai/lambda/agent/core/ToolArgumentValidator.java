package ai.lambda.agent.core;

@FunctionalInterface
public interface ToolArgumentValidator {
    void validate(String argumentsJson) throws Exception;
}
