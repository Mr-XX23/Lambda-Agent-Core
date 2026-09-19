package ai.lambda.agent.core;

import org.json.JSONObject;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Redis checkpoint store using atomic client-side compare-and-set primitives. */
public final class RedisCheckpointStore implements CheckpointStore {
    private final RedisCheckpointClient client;
    private final String keyPrefix;

    public RedisCheckpointStore(RedisCheckpointClient client) {
        this(client, "lambda:checkpoint:");
    }

    public RedisCheckpointStore(RedisCheckpointClient client, String keyPrefix) {
        this.client = Objects.requireNonNull(client);
        this.keyPrefix = Objects.requireNonNull(keyPrefix);
    }

    @Override
    public Optional<WorkflowCheckpoint> load(String executionId) {
        return client.get(key(executionId)).map(this::decode);
    }

    @Override
    public void save(WorkflowCheckpoint checkpoint) {
        String key = key(checkpoint.executionId());
        String encoded = encode(checkpoint);
        if (!client.setIfAbsent(key, encoded)) {
            client.get(key).ifPresentOrElse(previous -> {
                if (!client.compareAndSet(key, previous, encoded)) {
                    throw new OptimisticLockException(checkpoint.executionId(),
                            checkpoint.version() - 1, decode(previous).version());
                }
            }, () -> { throw new RuntimeException("Checkpoint disappeared during save"); });
        }
    }

    @Override
    public void save(WorkflowCheckpoint checkpoint, long expectedVersion) {
        String key = key(checkpoint.executionId());
        String encoded = encode(checkpoint);
        String previous = client.get(key).orElse(null);
        if (previous == null) {
            if (expectedVersion != 0 || !client.setIfAbsent(key, encoded)) {
                throw new OptimisticLockException(checkpoint.executionId(), expectedVersion, 0);
            }
            return;
        }
        WorkflowCheckpoint actual = decode(previous);
        if (actual.version() != expectedVersion
                || !client.compareAndSet(key, previous, encoded)) {
            throw new OptimisticLockException(checkpoint.executionId(), expectedVersion, actual.version());
        }
    }

    private String key(String executionId) {
        if (executionId == null || executionId.isBlank()) throw new IllegalArgumentException("executionId required");
        return keyPrefix + executionId;
    }

    private static String encode(WorkflowCheckpoint checkpoint) {
        return new JSONObject().put("executionId", checkpoint.executionId())
                .put("workflowName", checkpoint.workflowName()).put("nextStep", checkpoint.nextStep())
                .put("status", checkpoint.status().name()).put("state", new JSONObject(checkpoint.state()))
                .put("error", checkpoint.error()).put("version", checkpoint.version()).toString();
    }

    private WorkflowCheckpoint decode(String value) {
        JSONObject json = new JSONObject(value);
        Map<String, Object> state = json.getJSONObject("state").toMap();
        return new WorkflowCheckpoint(json.getString("executionId"), json.getString("workflowName"),
                json.getInt("nextStep"), WorkflowStatus.valueOf(json.getString("status")), state,
                json.optString("error", null), json.getLong("version"));
    }
}
