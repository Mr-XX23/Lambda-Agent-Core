package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;

import java.util.List;
import java.util.Objects;

public final class ToolInvocationContext {

    private final String toolCallId;
    private final String argumentsJson;
    private final AgentSession session;

    public ToolInvocationContext(String toolCallId, String argumentsJson, AgentSession session) {
        this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        this.argumentsJson = argumentsJson == null ? "{}" : argumentsJson;
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getArgumentsJson() {
        return argumentsJson;
    }

    public AgentSession getSession() {
        return session;
    }

    /**
     * Convenience: current message history for tools that need context.
     */
    public List<Message> getMessages() {
        return session.getMessages();
    }
}
