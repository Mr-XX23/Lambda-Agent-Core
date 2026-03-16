package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ToolCall;

public interface AgentEventListener {
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
}
