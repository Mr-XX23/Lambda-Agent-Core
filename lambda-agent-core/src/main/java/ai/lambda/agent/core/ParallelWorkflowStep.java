package ai.lambda.agent.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

final class ParallelWorkflowStep implements WorkflowStep {
    private final String joinName;
    private final List<WorkflowStepSpec> branches;

    ParallelWorkflowStep(String joinName, List<WorkflowStepSpec> branches) {
        this.joinName = joinName;
        this.branches = List.copyOf(branches);
        if (this.branches.isEmpty()) throw new IllegalArgumentException("branches must not be empty");
    }

    @Override
    public void execute(WorkflowContext context, CancellationToken cancellationToken) throws Exception {
        Map<String, Object> branchStates = new LinkedHashMap<>();
        Object existing = context.get("parallel." + joinName);
        if (existing instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                branchStates.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = branches.stream().map(branch -> executor.submit(() -> {
                cancellationToken.throwIfCancelled();
                WorkflowContext branchContext = new WorkflowContext(context.getState());
                branchContext.put("parallel.branch", branch.name());
                Workflow.executeWithPolicyForBranch(branch, branchContext, cancellationToken);
                synchronized (branchStates) {
                    branchStates.put(branch.name(), Map.copyOf(branchContext.getState()));
                }
                return null;
            })).toList();
            for (var future : futures) {
                try {
                    future.get();
                } catch (ExecutionException error) {
                    cancellationToken.cancel();
                    throw error.getCause() instanceof Exception exception
                            ? exception : new RuntimeException(error.getCause());
                }
            }
        }
        context.put("parallel." + joinName, branchStates);
        context.put("parallel." + joinName + ".joined", true);
    }
}
