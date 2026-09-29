package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;

import java.util.List;

/**
 * Drops the oldest messages (preserving the SYSTEM prompt) until the token count is within limits.
 * A tool call and its results are kept or dropped together, and the kept history always
 * starts with a user message. The latest user message is always kept, even if it alone
 * is over the limit.
 */
public final class TokenLimitStrategy implements ContextStrategy {

    private final int maxTokens;

    public TokenLimitStrategy(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    @Override
    public List<Message> optimize(List<Message> history, ModelClient modelClient) {
        return HistoryTrimmer.trim(history, m -> modelClient.countTokens(List.of(m)), maxTokens);
    }
}
