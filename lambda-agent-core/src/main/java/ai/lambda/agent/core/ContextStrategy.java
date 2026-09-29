package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;

import java.util.List;

/**
 * Strategy for optimizing conversation history before sending it to the LLM.
 * Used for context window management (truncation, summarization, etc.).
 */
public interface ContextStrategy {
    /**
     * Optimizes the message history.
     * 
     * @param history The full conversation history.
     * @param modelClient The model client (can be used for token counting or summarization).
     * @return A new list containing the optimized messages.
     */
    List<Message> optimize(List<Message> history, ModelClient modelClient);
}
