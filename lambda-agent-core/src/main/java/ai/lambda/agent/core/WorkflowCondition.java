package ai.lambda.agent.core;

@FunctionalInterface
public interface WorkflowCondition {
    boolean test(WorkflowContext context);
}
