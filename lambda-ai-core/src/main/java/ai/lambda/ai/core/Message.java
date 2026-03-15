package ai.lambda.ai.core;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Message {
    private final Role role;
    private final String content;
    private final String toolCallId;
    private final String toolCallName;
    private final List<ToolCall> toolCalls;

    public Message(Role role, String content, String toolCallId, String toolCallName, List<ToolCall> toolCalls) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId;
        this.toolCallName = toolCallName;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
    }

    public Message(Role role, String content, String toolCallId) {
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.content = content == null ? "" : content;
        this.toolCallId = toolCallId;
        this.toolCallName = null;
        this.toolCalls = Collections.emptyList();
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolCallName() {
        return toolCallName;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    @Override
    public String toString() {
        return "Message{" +
                "role=" + role +
                ", content='" + content + '\'' +
                ", toolCallId='" + toolCallId + '\'' +
                '}';
    }
}
