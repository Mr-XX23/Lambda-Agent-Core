package ai.lambda.agent.core;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcCheckpointStoreTest {

    private static JdbcCheckpointStore store() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcCheckpointStore store = new JdbcCheckpointStore(dataSource);
        store.initializeSchema();
        store.initializeSchema();
        return store;
    }

    @Test
    void savesAndLoadsCheckpoints() {
        JdbcCheckpointStore store = store();
        assertTrue(store.load("run-1").isEmpty());

        store.save(new WorkflowCheckpoint("run-1", "deploy", 2, WorkflowStatus.RUNNING,
                Map.of("branch", "main", "attempt", 1), null, 1));
        store.save(new WorkflowCheckpoint("run-1", "deploy", 3, WorkflowStatus.FAILED,
                Map.of("branch", "main"), "tests failed", 2), 1);

        WorkflowCheckpoint loaded = store.load("run-1").orElseThrow();
        assertEquals(3, loaded.nextStep());
        assertEquals(WorkflowStatus.FAILED, loaded.status());
        assertEquals("tests failed", loaded.error());
        assertEquals(Map.of("branch", "main"), loaded.state());
        assertEquals(2, loaded.version());
    }

    @Test
    void rejectsSavesAtAStaleVersion() {
        JdbcCheckpointStore store = store();
        store.save(new WorkflowCheckpoint("run-1", "deploy", 1, WorkflowStatus.RUNNING, Map.of(), null, 1));
        store.save(new WorkflowCheckpoint("run-1", "deploy", 2, WorkflowStatus.RUNNING, Map.of(), null, 2), 1);

        assertThrows(OptimisticLockException.class, () -> store.save(
                new WorkflowCheckpoint("run-1", "deploy", 9, WorkflowStatus.RUNNING, Map.of(), null, 2), 1));
        assertEquals(2, store.load("run-1").orElseThrow().nextStep());
    }

    @Test
    void rejectsUnsafeTableNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcCheckpointStore(new JdbcDataSource(), "t; DROP TABLE x"));
    }
}
