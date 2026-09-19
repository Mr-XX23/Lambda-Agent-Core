package ai.lambda.agent.core;

@FunctionalInterface
public interface WorkflowApprovalHandler {
    boolean approve(String executionId, String stepName, WorkflowContext context);
}
