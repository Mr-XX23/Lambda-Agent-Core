package ai.lambda.agent.core;

import java.util.Set;

@FunctionalInterface
public interface ToolPermissionPolicy {
    boolean allowed(String sessionId, AgentTool tool, Set<ToolCapability> capabilities);

    default PermissionDecision evaluate(String sessionId, AgentTool tool, Set<ToolCapability> capabilities) {
        return allowed(sessionId, tool, capabilities)
                ? PermissionDecision.allow("policy allowed tool")
                : PermissionDecision.deny("policy denied tool");
    }

    static ToolPermissionPolicy allowAll() {
        return (sessionId, tool, capabilities) -> true;
    }
}
