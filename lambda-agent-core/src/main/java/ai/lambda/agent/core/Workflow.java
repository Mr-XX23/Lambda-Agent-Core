package ai.lambda.agent.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Workflow {
    private final String name;
    private final List<WorkflowStep> steps;
    private final CheckpointStore checkpointStore;

    public Workflow(String name, List<WorkflowStep> steps, CheckpointStore checkpointStore) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.steps = steps == null ? List.of() : List.copyOf(steps);
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("Workflow must contain at least one step");
        }
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
    }

    public WorkflowResult run() {
        return run(UUID.randomUUID().toString(), Map.of(), new CancellationToken());
    }

    public WorkflowResult run(String executionId, Map<String, Object> initialState,
                              CancellationToken cancellationToken) {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken must not be null");
        java.util.Optional<WorkflowCheckpoint> existing = checkpointStore.load(executionId);
        WorkflowCheckpoint checkpoint = existing.orElseGet(() -> WorkflowCheckpoint.start(executionId, name));
        if (!name.equals(checkpoint.workflowName())) {
            throw new IllegalArgumentException("Execution belongs to workflow '" + checkpoint.workflowName() + "'");
        }
        if (checkpoint.status() == WorkflowStatus.COMPLETED) {
            return new WorkflowResult(executionId, checkpoint.status(), checkpoint);
        }

        WorkflowContext context = new WorkflowContext(existing.isEmpty() ? initialState : checkpoint.state());
        for (int stepIndex = checkpoint.nextStep(); stepIndex < steps.size(); stepIndex++) {
            cancellationToken.throwIfCancelled();
            try {
                steps.get(stepIndex).execute(context, cancellationToken);
                checkpoint = new WorkflowCheckpoint(executionId, name, stepIndex + 1,
                        stepIndex + 1 == steps.size() ? WorkflowStatus.COMPLETED : WorkflowStatus.RUNNING,
                        context.getState(), null);
                checkpointStore.save(checkpoint);
            } catch (Exception error) {
                checkpoint = new WorkflowCheckpoint(executionId, name, stepIndex,
                        WorkflowStatus.FAILED, context.getState(), error.getMessage());
                checkpointStore.save(checkpoint);
                throw new WorkflowExecutionException(executionId, stepIndex, error);
            }
        }
        return new WorkflowResult(executionId, checkpoint.status(), checkpoint);
    }
}
