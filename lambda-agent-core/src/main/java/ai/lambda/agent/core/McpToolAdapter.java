package ai.lambda.agent.core;

import java.util.Objects;

/** Adapts an MCP tool declaration and invocation callback to AgentTool. */
public final class McpToolAdapter implements AgentTool {
    private final String name;
    private final String description;
    private final String schema;
    private final McpTool tool;

    public McpToolAdapter(String name, String description, String schema, McpTool tool) {
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.schema = Objects.requireNonNull(schema);
        this.tool = Objects.requireNonNull(tool);
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public String getJsonSchema() { return schema; }
    @Override public ToolResult execute(ToolInvocationContext context) throws Exception {
        return tool.call(context.getArgumentsJson(), context);
    }
}
