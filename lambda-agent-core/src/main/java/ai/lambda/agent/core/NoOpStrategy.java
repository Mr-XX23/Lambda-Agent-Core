package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;

import java.util.List;

/**
 * Default strategy that does nothing.
 */
public final class NoOpStrategy implements ContextStrategy {
    @Override
    public List<Message> optimize(List<Message> history, ModelClient modelClient) {
        return history;
    }
}
