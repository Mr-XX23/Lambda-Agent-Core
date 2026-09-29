package ai.lambda.agent.core;

import java.util.Map;
import java.util.Objects;

public record WorkflowCheckpoint(
        String executionId,
        String workflowName,
        int nextStep,
        WorkflowStatus status,
        Map<String, Object> state,
        String error,
        long version) {
    public WorkflowCheckpoint(String executionId, String workflowName, int nextStep,
                              WorkflowStatus status, Map<String, Object> state, String error) {
        this(executionId, workflowName, nextStep, status, state, error, 0);
    }
    public WorkflowCheckpoint {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(workflowName, "workflowName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        state = state == null ? Map.of() : Map.copyOf(state);
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public static WorkflowCheckpoint start(String executionId, String workflowName) {
        return new WorkflowCheckpoint(executionId, workflowName, 0, WorkflowStatus.RUNNING, Map.of(), null, 0);
    }
}
