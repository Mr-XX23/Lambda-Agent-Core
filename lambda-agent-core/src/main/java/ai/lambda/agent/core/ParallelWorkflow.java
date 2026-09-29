package ai.lambda.agent.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public final class ParallelWorkflow {
    private final List<WorkflowStepSpec> branches;
    private final CheckpointStore checkpointStore;

    public ParallelWorkflow(List<WorkflowStepSpec> branches, CheckpointStore checkpointStore) {
        this.branches = List.copyOf(branches);
        if (this.branches.isEmpty()) throw new IllegalArgumentException("branches must not be empty");
        this.checkpointStore = checkpointStore;
    }

    public WorkflowResult run(String executionId, Map<String, Object> initialState,
                              CancellationToken token) {
        WorkflowContext context = new WorkflowContext(initialState);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = branches.stream().map(branch -> executor.submit(() -> {
                branch.step().execute(context, token);
                return true;
            })).toList();
            for (var future : futures) future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel workflow interrupted", error);
        } catch (ExecutionException error) {
            throw new RuntimeException("Parallel workflow branch failed", error.getCause());
        }
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(executionId, "parallel", branches.size(),
                WorkflowStatus.COMPLETED, context.getState(), null, 1);
        checkpointStore.save(checkpoint);
        return new WorkflowResult(executionId, WorkflowStatus.COMPLETED, checkpoint);
    }
}
