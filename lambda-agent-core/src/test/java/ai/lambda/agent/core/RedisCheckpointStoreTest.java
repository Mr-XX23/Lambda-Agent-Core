package ai.lambda.agent.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class RedisCheckpointStoreTest {

    /** What an application would bind to Jedis or Lettuce, here over a map. */
    static final class MapRedis implements RedisCheckpointClient {
        final Map<String, String> values = new ConcurrentHashMap<>();
        /** When set, runs once just before the next compareAndSet, to imitate another writer getting in first. */
        Runnable beforeCompareAndSet;

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public boolean setIfAbsent(String key, String value) {
            return values.putIfAbsent(key, value) == null;
        }

        @Override
        public boolean compareAndSet(String key, String expectedValue, String value) {
            if (beforeCompareAndSet != null) {
                Runnable interference = beforeCompareAndSet;
                beforeCompareAndSet = null;
                interference.run();
            }
            return values.replace(key, expectedValue, value);
        }
    }

    private final MapRedis redis = new MapRedis();
    private final RedisCheckpointStore store = new RedisCheckpointStore(redis);

    private static WorkflowCheckpoint checkpoint(int step, long version) {
        return new WorkflowCheckpoint("run-1", "deploy", step, WorkflowStatus.RUNNING, Map.of("branch", "main"), null, version);
    }

    @Test
    void savesAndLoadsUnderThePrefixedKey() {
        assertTrue(store.load("run-1").isEmpty());

        store.save(new WorkflowCheckpoint("run-1", "deploy", 2, WorkflowStatus.FAILED,
                Map.of("branch", "main", "attempt", 3), "tests failed", 4));

        assertTrue(redis.values.containsKey("lambda:checkpoint:run-1"));
        WorkflowCheckpoint loaded = store.load("run-1").orElseThrow();
        assertEquals("deploy", loaded.workflowName());
        assertEquals(2, loaded.nextStep());
        assertEquals(WorkflowStatus.FAILED, loaded.status());
        assertEquals(Map.of("branch", "main", "attempt", 3), loaded.state());
        assertEquals("tests failed", loaded.error());
        assertEquals(4, loaded.version());

        new RedisCheckpointStore(redis, "app:").save(checkpoint(0, 1));
        assertTrue(redis.values.containsKey("app:run-1"));
    }

    @Test
    void aCheckpointWithoutAnErrorLoadsWithoutOne() {
        store.save(checkpoint(1, 1));
        assertNull(store.load("run-1").orElseThrow().error());
    }

    @Test
    void plainSaveOverwritesTheStoredCheckpoint() {
        store.save(checkpoint(1, 1));
        store.save(checkpoint(2, 2));
        assertEquals(2, store.load("run-1").orElseThrow().nextStep());
    }

    @Test
    void versionedSaveRequiresTheExpectedVersion() {
        store.save(checkpoint(1, 1), 0); // 0: nothing stored yet
        store.save(checkpoint(2, 2), 1);

        assertThrows(OptimisticLockException.class, () -> store.save(checkpoint(9, 2), 1), "stale version");
        assertThrows(OptimisticLockException.class, () -> store.save(checkpoint(9, 1), 0), "already exists");
        assertEquals(2, store.load("run-1").orElseThrow().nextStep());
    }

    @Test
    void versionedSaveOfAMissingCheckpointRequiresVersionZero() {
        assertThrows(OptimisticLockException.class, () -> store.save(checkpoint(1, 5), 4));
        assertTrue(store.load("run-1").isEmpty());
    }

    @Test
    void aWriterThatGetsInFirstCausesAConflict() {
        store.save(checkpoint(1, 1));
        RedisCheckpointStore other = new RedisCheckpointStore(redis);

        redis.beforeCompareAndSet = () -> redis.values.put("lambda:checkpoint:run-1", redis.values.get("lambda:checkpoint:run-1") + " ");
        assertThrows(OptimisticLockException.class, () -> store.save(checkpoint(2, 2), 1));

        redis.beforeCompareAndSet = () -> redis.values.put("lambda:checkpoint:run-1", redis.values.get("lambda:checkpoint:run-1") + " ");
        assertThrows(OptimisticLockException.class, () -> other.save(checkpoint(3, 3)));
    }

    @Test
    void rejectsBlankExecutionIds() {
        assertThrows(IllegalArgumentException.class, () -> store.load(" "));
        assertThrows(NullPointerException.class, () -> new RedisCheckpointStore(null));
    }

    @Test
    void worksAsAWorkflowCheckpointStore() {
        Workflow workflow = new Workflow("two-steps", java.util.List.<WorkflowStep>of(
                (context, token) -> context.put("a", 1),
                (context, token) -> context.put("b", 2)), store);

        WorkflowResult result = workflow.run("exec-1", Map.of(), new CancellationToken());

        assertEquals(WorkflowStatus.COMPLETED, result.status());
        WorkflowCheckpoint stored = store.load("exec-1").orElseThrow();
        assertEquals(2, stored.nextStep());
        assertEquals(2, stored.state().get("b"));
    }
}
