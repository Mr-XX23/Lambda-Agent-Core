package ai.lambda.agent.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class McpToolRegistry {
    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    public void register(AgentTool tool) {
        Objects.requireNonNull(tool);
        if (tools.putIfAbsent(tool.getName(), tool) != null) {
            throw new IllegalArgumentException("MCP tool already registered: " + tool.getName());
        }
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown MCP tool: " + name);
        return tool;
    }

    public Map<String, AgentTool> snapshot() { return Map.copyOf(tools); }
}
