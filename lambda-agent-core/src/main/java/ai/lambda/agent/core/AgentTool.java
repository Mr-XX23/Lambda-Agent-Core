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

    default ToolPolicy getPolicy() {
        return ToolPolicy.unrestricted();
    }

    default java.util.Set<ToolCapability> getCapabilities() {
        return getPolicy().capabilities();
    }

    /**
     * Whether this tool may run at the same time as other tools the model asked for in the same
     * reply. Return true only if running it alongside any other tool, including another call of
     * itself, cannot change the outcome: it only reads or looks things up, and keeps no state that
     * calls share. Tools that write files, change records, or update session data should keep the
     * default, false; they then run alone, in the order the model asked.
     */
    default boolean isParallelSafe() {
        return false;
    }

    default ToolArgumentValidator getArgumentValidator() {
        return argumentsJson -> {};
    }

    default TypedToolInput<?> getTypedInputSchema() {
        return null;
    }

    /**
     * Execute the tool.
     *
     * @param context provides toolCallId, raw arguments JSON, and session.
     */
    ToolResult execute(ToolInvocationContext context) throws Exception;
}
