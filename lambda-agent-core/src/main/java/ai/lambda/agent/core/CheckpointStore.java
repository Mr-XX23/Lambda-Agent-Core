package ai.lambda.agent.core;

import java.util.Optional;

public interface CheckpointStore {
    Optional<WorkflowCheckpoint> load(String executionId);
    void save(WorkflowCheckpoint checkpoint);
}
