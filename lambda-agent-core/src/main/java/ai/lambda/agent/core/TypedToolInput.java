package ai.lambda.agent.core;

@FunctionalInterface
public interface TypedToolInput<T> {
    T parse(String argumentsJson) throws Exception;
}
