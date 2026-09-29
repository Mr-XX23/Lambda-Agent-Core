package ai.lambda.agent.core;

import java.util.Objects;

/** Adapts an MCP tool declaration and invocation callback to AgentTool. */
public final class McpToolAdapter implements AgentTool {
    private final String name;
    private final String description;
    private final String schema;
    private final ToolPolicy policy;
    private final McpTool tool;

    public McpToolAdapter(String name, String description, String schema, McpTool tool) {
        this(name, description, schema, ToolPolicy.unrestricted(), tool);
    }

    /** @param policy approval, timeout, result size and capabilities for this tool */
    public McpToolAdapter(String name, String description, String schema, ToolPolicy policy, McpTool tool) {
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.schema = Objects.requireNonNull(schema);
        this.policy = Objects.requireNonNull(policy);
        this.tool = Objects.requireNonNull(tool);
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public String getJsonSchema() { return schema; }
    @Override public ToolPolicy getPolicy() { return policy; }
    @Override public ToolResult execute(ToolInvocationContext context) throws Exception {
        return tool.call(context.getArgumentsJson(), context);
    }
}
