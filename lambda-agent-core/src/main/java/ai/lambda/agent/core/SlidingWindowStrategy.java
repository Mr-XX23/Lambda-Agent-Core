package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;

import java.util.List;

/**
 * Keeps the SYSTEM prompt and roughly the last N messages (N includes the system prompt).
 * A tool call and its results are kept or dropped together, and the kept history always
 * starts with a user message, so it can come out a little shorter than N. The latest user
 * message is always kept, even if that means going over N.
 */
public final class SlidingWindowStrategy implements ContextStrategy {

    private final int maxMessages;

    public SlidingWindowStrategy(int maxMessages) {
        if (maxMessages < 1) throw new IllegalArgumentException("maxMessages must be at least 1");
        this.maxMessages = maxMessages;
    }

    @Override
    public List<Message> optimize(List<Message> history, ModelClient modelClient) {
        if (history.size() <= maxMessages) {
            return history;
        }
        return HistoryTrimmer.trim(history, m -> 1, maxMessages);
    }
}
