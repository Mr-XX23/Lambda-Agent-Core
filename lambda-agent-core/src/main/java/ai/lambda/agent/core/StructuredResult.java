package ai.lambda.agent.core;

/**
 * The result of a run with {@link StructuredOutput}: the parsed answer, and the run it came from
 * (session, run id, iterations; {@code run().getFinalText()} holds the answer as JSON).
 */
public record StructuredResult<T>(T value, AgentResult run) {
}
