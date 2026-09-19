package ai.lambda.agent.core;

import java.util.List;

public final class ToolPolicyComposition {
    private ToolPolicyComposition() {}

    public static ToolPermissionPolicy allOf(List<ToolPermissionPolicy> policies) {
        List<ToolPermissionPolicy> safe = List.copyOf(policies);
        return new ToolPermissionPolicy() {
            @Override public boolean allowed(String sessionId, AgentTool tool, java.util.Set<ToolCapability> capabilities) {
                return evaluate(sessionId, tool, capabilities).allowed();
            }
            @Override public PermissionDecision evaluate(String sessionId, AgentTool tool,
                                                         java.util.Set<ToolCapability> capabilities) {
            for (ToolPermissionPolicy policy : safe) {
                PermissionDecision decision = policy.evaluate(sessionId, tool, capabilities);
                if (!decision.allowed()) return decision;
            }
            return PermissionDecision.allow("all policies allowed");
            }
        };
    }

    public static ToolPermissionPolicy anyOf(List<ToolPermissionPolicy> policies) {
        List<ToolPermissionPolicy> safe = List.copyOf(policies);
        return new ToolPermissionPolicy() {
            @Override public boolean allowed(String sessionId, AgentTool tool, java.util.Set<ToolCapability> capabilities) {
                return evaluate(sessionId, tool, capabilities).allowed();
            }
            @Override public PermissionDecision evaluate(String sessionId, AgentTool tool,
                                                         java.util.Set<ToolCapability> capabilities) {
            StringBuilder reasons = new StringBuilder();
            for (ToolPermissionPolicy policy : safe) {
                PermissionDecision decision = policy.evaluate(sessionId, tool, capabilities);
                if (decision.allowed()) return decision;
                if (reasons.length() > 0) reasons.append("; ");
                reasons.append(decision.reason());
            }
            return PermissionDecision.deny(reasons.toString());
            }
        };
    }
}
