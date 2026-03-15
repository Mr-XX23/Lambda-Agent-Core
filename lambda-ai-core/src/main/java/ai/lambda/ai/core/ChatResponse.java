package ai.lambda.ai.core;

import java.util.Collections;
import java.util.List;

public final class ChatResponse {

    private final Message assistantMessage;
    private final List<ToolCall> toolCalls;

    public ChatResponse(Message assistantMessage, List<ToolCall> toolCalls) {
        this.assistantMessage = assistantMessage;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
    }

    public Message getAssistantMessage() {
        return assistantMessage;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
}
