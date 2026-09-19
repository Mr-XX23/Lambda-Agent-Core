package ai.lambda.agent.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Workflow {
    private final String name;
    private final List<WorkflowStep> steps;
    private final List<WorkflowStepSpec> specifications;
    private final CheckpointStore checkpointStore;

    public Workflow(String name, List<WorkflowStep> steps, CheckpointStore checkpointStore) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.steps = steps == null ? List.of() : List.copyOf(steps);
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("Workflow must contain at least one step");
        }
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.specifications = this.steps.stream()
                .map(step -> new WorkflowStepSpec("step-" + this.steps.indexOf(step), step))
                .toList();
    }

    public Workflow(String name, List<WorkflowStepSpec> specifications,
                    CheckpointStore checkpointStore, boolean configured) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.specifications = specifications == null ? List.of() : List.copyOf(specifications);
        if (this.specifications.isEmpty()) {
            throw new IllegalArgumentException("Workflow must contain at least one step");
        }
        this.steps = this.specifications.stream().map(WorkflowStepSpec::step).toList();
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
                WorkflowStepSpec specification = specifications.get(stepIndex);
                if (specification.condition().test(context)) {
                    executeWithPolicy(specification, context, cancellationToken);
                }
                checkpoint = new WorkflowCheckpoint(executionId, name, stepIndex + 1,
                        stepIndex + 1 == steps.size() ? WorkflowStatus.COMPLETED : WorkflowStatus.RUNNING,
                        context.getState(), null, checkpoint.version() + 1);
                checkpointStore.save(checkpoint, checkpoint.version() - 1);
            } catch (Exception error) {
                checkpoint = new WorkflowCheckpoint(executionId, name, stepIndex,
                        WorkflowStatus.FAILED, context.getState(), error.getMessage(), checkpoint.version() + 1);
                checkpointStore.save(checkpoint, checkpoint.version() - 1);
                throw new WorkflowExecutionException(executionId, stepIndex, error);
            }
        }
        return new WorkflowResult(executionId, checkpoint.status(), checkpoint);
    }

    private static void executeWithPolicy(WorkflowStepSpec specification, WorkflowContext context,
                                           CancellationToken cancellationToken) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= specification.retryPolicy().maxAttempts(); attempt++) {
            cancellationToken.throwIfCancelled();
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var future = executor.submit(() -> {
                    specification.step().execute(context, cancellationToken);
                    return null;
                });
                try {
                    future.get(specification.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    return;
                } catch (java.util.concurrent.TimeoutException timeout) {
                    future.cancel(true);
                    lastError = new RuntimeException("Workflow step '" + specification.name() + "' timed out", timeout);
                } catch (java.util.concurrent.ExecutionException failure) {
                    lastError = failure.getCause() instanceof Exception exception
                            ? exception : new RuntimeException(failure.getCause());
                }
            }
            if (attempt < specification.retryPolicy().maxAttempts()) {
                java.time.Duration delay = specification.retryPolicy().delayBeforeAttempt(attempt + 1);
                if (!delay.isZero()) {
                    Thread.sleep(delay.toMillis());
                }
            }
        }
        throw lastError;
    }
}
