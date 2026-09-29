package ai.lambda.agent.core;

public final class ApprovalWorkflowStep implements WorkflowStep {
    private final String stepName;
    private final WorkflowApprovalHandler handler;

    public ApprovalWorkflowStep(String stepName, WorkflowApprovalHandler handler) {
        this.stepName = stepName;
        this.handler = handler;
    }

    @Override
    public void execute(WorkflowContext context, CancellationToken token) {
        token.throwIfCancelled();
        String executionId = String.valueOf(context.get("executionId"));
        if (!handler.approve(executionId, stepName, context)) {
            throw new ApprovalRequiredException("Approval required for workflow step '" + stepName + "'");
        }
    }
}
