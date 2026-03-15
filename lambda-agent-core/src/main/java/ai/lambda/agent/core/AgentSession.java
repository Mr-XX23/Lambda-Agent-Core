package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AgentSession {
    private final String id;
    private final List<Message> messages = new ArrayList<>();

    public AgentSession(String id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
    }

    public String getId() {
        return id;
    }

    public List<Message> getMessages() {
        return messages;
    }
}
