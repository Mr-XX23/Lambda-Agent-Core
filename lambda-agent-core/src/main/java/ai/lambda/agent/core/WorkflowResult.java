package ai.lambda.agent.core;

public record WorkflowResult(String executionId, WorkflowStatus status, WorkflowCheckpoint checkpoint) {
}
