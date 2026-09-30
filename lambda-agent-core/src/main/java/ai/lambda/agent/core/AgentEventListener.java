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
    default void onToolAudit(ToolAuditEvent event) {}

    // Fired when a new text chunk is received from the model (streaming)
    default void onAssistantDelta(String delta) {}

    /**
     * The reply streamed so far is abandoned: a retry of a failed model call said something else,
     * and its text follows from the start. Clear what was shown of this reply. (A retry that
     * repeats the same text does not trigger this; only its new text is sent.)
     */
    default void onAssistantRestart() {}

    // Subagent events. Subagents run in parallel, so these may be called from several threads at once.
    // depth is 1 for subagents of the main agent, 2 for their subagents, and so on.

    // Fired when a subagent starts a delegated task
    default void onSubagentStart(String subagent, String task, int depth) {}

    // Fired when a subagent finishes; result.getSession() holds its full transcript
    default void onSubagentEnd(String subagent, int depth, AgentResult result) {}

    // Fired if a subagent's run fails; the other tasks of the same call keep running
    default void onSubagentError(String subagent, int depth, Exception error) {}
}
