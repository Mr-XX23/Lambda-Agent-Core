package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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

    // Messages never change, so each is counted once. Counting can be a network call per message
    // (Gemini, Claude); without this, every model call would recount the whole history.
    // Weak keys: entries disappear when their messages are no longer referenced.
    private final Map<Message, Integer> counts = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public List<Message> optimize(List<Message> history, ModelClient modelClient) {
        return HistoryTrimmer.trim(history, message -> {
            Integer count = counts.get(message);
            if (count == null) {
                count = modelClient.countTokens(List.of(message));
                counts.put(message, count);
            }
            return count;
        }, maxTokens);
    }
}
