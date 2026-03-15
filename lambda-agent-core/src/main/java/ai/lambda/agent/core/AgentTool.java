package ai.lambda.agent.core;

/**
 * AgentTool is a unit of capability the model can call.
 */
public interface AgentTool {

    /**
     * Name used in the model's tool_call.name.
     */
    String getName();

    /**
     * Human-readable description for the model.
     */
    String getDescription();

    /**
     * JSON schema (as String) describing the arguments object for this tool.
     */
    String getJsonSchema();

    /**
     * Execute the tool.
     *
     * @param context provides toolCallId, raw arguments JSON, and session.
     */
    ToolResult execute(ToolInvocationContext context) throws Exception;
}
