package ai.lambda.ai.core;

import java.util.Collections;
import java.util.List;

public final class ChatResponse {

    private final Message assistantMessage;
    private final List<ToolCall> toolCalls;
    private final FinishReason finishReason;
    private final ModelUsage usage;

    public ChatResponse(Message assistantMessage, List<ToolCall> toolCalls) {
        this(assistantMessage, toolCalls, FinishReason.UNKNOWN, ModelUsage.empty());
    }

    public ChatResponse(Message assistantMessage, List<ToolCall> toolCalls,
                        FinishReason finishReason, ModelUsage usage) {
        this.assistantMessage = assistantMessage;
        this.toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
        this.finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
        this.usage = usage == null ? ModelUsage.empty() : usage;
    }

    public Message getAssistantMessage() {
        return assistantMessage;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public FinishReason getFinishReason() {
        return finishReason;
    }

    public ModelUsage getUsage() {
        return usage;
    }
}
