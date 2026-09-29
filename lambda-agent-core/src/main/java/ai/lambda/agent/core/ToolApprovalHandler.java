package ai.lambda.agent.core;

@FunctionalInterface
public interface ToolApprovalHandler {
    boolean approve(String sessionId, ai.lambda.ai.core.ToolCall call);
}
