package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentSession {
    private final String id;
    private final List<Message> messages = new ArrayList<>();
    private final Map<String, Object> metadata = new HashMap<>();

    public AgentSession(String id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public String getId() {
        return id;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }
}
