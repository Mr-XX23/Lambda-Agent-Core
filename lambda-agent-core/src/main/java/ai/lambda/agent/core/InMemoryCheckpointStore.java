package ai.lambda.agent.core;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCheckpointStore implements CheckpointStore {
    private final Map<String, WorkflowCheckpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkflowCheckpoint> load(String executionId) {
        return Optional.ofNullable(checkpoints.get(executionId));
    }

    @Override
    public void save(WorkflowCheckpoint checkpoint) {
        checkpoints.put(checkpoint.executionId(), checkpoint);
    }
}
