package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ToolCall;

public interface AgentEventListener {
    default void onRunStart(String runId, String sessionId) {}
    default void onRunEnd(AgentResult result) {}
    default void onModelResponse(ai.lambda.ai.core.ChatResponse response) {}
    default void onModelRetry(int attempt, Exception error, java.time.Duration delay) {}
    // Fired when the agent begins a new model call loop
    default void onIterationStart(int iteration) {}

    // Fired when the model returns an assistant message
    default void onAssistantMessage(Message assistantMessage) {}

    // Fired right before a tool executes
    default void onToolStart(ToolCall call, ToolInvocationContext ctx) {}

    // Fired right after a tool successfully executes
    default void onToolEnd(ToolCall call, ToolResult result) {}

    // Fired if a tool throws an exception
    default void onToolError(ToolCall call, Exception error) {}

    // Fired when a new text chunk is received from the model (streaming)
    default void onAssistantDelta(String delta) {}
}
