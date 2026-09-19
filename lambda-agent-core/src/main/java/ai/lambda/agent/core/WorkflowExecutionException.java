package ai.lambda.agent.core;

public final class WorkflowExecutionException extends RuntimeException {
    private final String executionId;
    private final int step;

    public WorkflowExecutionException(String executionId, int step, Throwable cause) {
        super("Workflow execution '" + executionId + "' failed at step " + step, cause);
        this.executionId = executionId;
        this.step = step;
    }

    public String getExecutionId() {
        return executionId;
    }

    public int getStep() {
        return step;
    }
}
