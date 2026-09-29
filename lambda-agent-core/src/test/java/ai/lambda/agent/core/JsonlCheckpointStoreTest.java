package ai.lambda.agent.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonlCheckpointStoreTest {
    @TempDir
    Path directory;

    @Test
    void persistsCheckpointAcrossStoreInstances() {
        JsonlCheckpointStore first = new JsonlCheckpointStore(directory);
        first.save(new WorkflowCheckpoint("run-1", "workflow", 2,
                WorkflowStatus.RUNNING, Map.of("count", 3), null));

        WorkflowCheckpoint loaded = new JsonlCheckpointStore(directory)
                .load("run-1").orElseThrow();

        assertEquals(2, loaded.nextStep());
        assertEquals(3, loaded.state().get("count"));
    }

    @Test
    void rejectsUnsafeExecutionIds() {
        JsonlCheckpointStore store = new JsonlCheckpointStore(directory);

        assertThrows(IllegalArgumentException.class, () -> store.load("../outside"));
    }
}
