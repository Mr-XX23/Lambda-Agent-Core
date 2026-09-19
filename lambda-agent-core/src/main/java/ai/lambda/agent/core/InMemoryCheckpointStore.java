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

    @Override
    public synchronized void save(WorkflowCheckpoint checkpoint, long expectedVersion) {
        WorkflowCheckpoint current = checkpoints.get(checkpoint.executionId());
        long actual = current == null ? 0 : current.version();
        if (actual != expectedVersion) {
            throw new OptimisticLockException(checkpoint.executionId(), expectedVersion, actual);
        }
        checkpoints.put(checkpoint.executionId(), checkpoint);
    }
}
