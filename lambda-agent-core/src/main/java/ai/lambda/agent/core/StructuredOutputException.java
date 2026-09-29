package ai.lambda.agent.core;

import java.util.List;

/**
 * Thrown when a run with {@link StructuredOutput} reaches its iteration limit without a valid
 * answer. The session has been saved; {@link #getResult()} gives access to it.
 */
public final class StructuredOutputException extends RuntimeException {

    private final AgentResult result;
    private final List<String> lastProblems;

    public StructuredOutputException(String message, AgentResult result, List<String> lastProblems) {
        super(message);
        this.result = result;
        this.lastProblems = List.copyOf(lastProblems);
    }

    public AgentResult getResult() {
        return result;
    }

    /** What was wrong with the last rejected answer, or empty if the model never submitted one. */
    public List<String> getLastProblems() {
        return lastProblems;
    }
}
