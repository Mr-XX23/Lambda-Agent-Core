package ai.lambda.ai.anthropic;

import ai.lambda.ai.core.ModelProvider;

import java.util.List;

/** Makes Claude available to {@link ai.lambda.ai.core.Models} as {@code anthropic} or {@code claude}. */
public final class AnthropicModelProviders implements ModelProvider.Registry {

    @Override
    public List<ModelProvider> providers() {
        return List.of(new ModelProvider("anthropic", List.of("claude"), "ANTHROPIC_API_KEY",
                AnthropicModelClient.DEFAULT_MODEL, AnthropicModelClient::new));
    }
}
