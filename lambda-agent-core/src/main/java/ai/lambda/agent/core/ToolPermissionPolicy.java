package ai.lambda.agent.core;

import java.util.Set;

@FunctionalInterface
public interface ToolPermissionPolicy {
    boolean allowed(String sessionId, AgentTool tool, Set<ToolCapability> capabilities);

    static ToolPermissionPolicy allowAll() {
        return (sessionId, tool, capabilities) -> true;
    }
}
