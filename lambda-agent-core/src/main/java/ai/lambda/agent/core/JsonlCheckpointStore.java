package ai.lambda.agent.core;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Objects;

public final class JsonlCheckpointStore implements CheckpointStore {
    private final Path storageDir;

    public JsonlCheckpointStore(Path storageDir) {
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir must not be null")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException error) {
            throw new RuntimeException("Failed to create checkpoint directory", error);
        }
    }

    @Override
    public Optional<WorkflowCheckpoint> load(String executionId) {
        Path file = pathFor(executionId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            java.util.Map<String, Object> state = new java.util.HashMap<>();
            JSONObject stateJson = json.optJSONObject("state");
            if (stateJson != null) {
                for (String key : stateJson.keySet()) {
                    state.put(key, stateJson.get(key));
                }
            }
            return Optional.of(new WorkflowCheckpoint(
                    json.getString("executionId"),
                    json.getString("workflowName"),
                    json.getInt("nextStep"),
                    WorkflowStatus.valueOf(json.getString("status")),
                    state,
                    json.optString("error", null),
                    json.optLong("version", 0)));
        } catch (Exception error) {
            throw new RuntimeException("Failed to load checkpoint " + executionId, error);
        }
    }

    @Override
    public void save(WorkflowCheckpoint checkpoint) {
        Path target = pathFor(checkpoint.executionId());
        Path temporary = null;
        try {
            JSONObject json = new JSONObject();
            json.put("executionId", checkpoint.executionId());
            json.put("workflowName", checkpoint.workflowName());
            json.put("nextStep", checkpoint.nextStep());
            json.put("status", checkpoint.status().name());
            json.put("version", checkpoint.version());
            json.put("state", new JSONObject(checkpoint.state()));
            if (checkpoint.error() != null) {
                json.put("error", checkpoint.error());
            }

            temporary = Files.createTempFile(storageDir, "." + target.getFileName(), ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new RuntimeException("Failed to save checkpoint " + checkpoint.executionId(), error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    throw new RuntimeException("Failed to clean temporary checkpoint", cleanupError);
                }
            }
        }
    }

    private Path pathFor(String executionId) {
        if (executionId == null || executionId.isBlank()
                || !executionId.matches("[A-Za-z0-9._-]+")
                || executionId.equals(".") || executionId.equals("..")) {
            throw new IllegalArgumentException("Invalid execution id: " + executionId);
        }
        return storageDir.resolve(executionId + ".checkpoint.json");
    }
}
