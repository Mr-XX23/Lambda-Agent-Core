package ai.lambda.agent.core;

@FunctionalInterface
public interface WorkflowStep {
    void execute(WorkflowContext context, CancellationToken cancellationToken) throws Exception;
}
