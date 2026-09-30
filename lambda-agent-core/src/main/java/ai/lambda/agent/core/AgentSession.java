package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AgentSession {
    private final String id;
    private final List<Message> messages = new ArrayList<>();
    // Tools may run in parallel and share this map, so it must be safe to use from several threads.
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    public AgentSession(String id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public String getId() {
        return id;
    }

    public List<Message> getMessages() {
        return messages;
    }

    /**
     * State that tools keep with the session (for example a todo list). Safe to read and write
     * from tools running in parallel; use {@code compute} or {@code merge} to update a value
     * based on its current one. Keys and values must not be null: remove a key instead.
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
