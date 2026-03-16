package ai.lambda.agent.core;

/**
 * Determines how the agent loop handles tool execution errors.
 */
public enum ToolErrorStrategy {

    /**
     * Send the error text back to the model as a TOOL message so it can
     * self-correct. This is the default behaviour.
     */
    SEND_TO_MODEL,

    /**
     * Immediately re-throw the exception, aborting the agent loop.
     */
    THROW
}
