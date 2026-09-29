package ai.lambda.agent.core;

@FunctionalInterface
public interface McpTool {
    ToolResult call(String argumentsJson, ToolInvocationContext context) throws Exception;
}
