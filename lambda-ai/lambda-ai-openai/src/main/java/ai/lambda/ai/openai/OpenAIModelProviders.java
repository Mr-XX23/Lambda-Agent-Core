package ai.lambda.ai.openai;

import ai.lambda.ai.core.ModelProvider;

import java.util.List;

/** Makes OpenAI available to {@link ai.lambda.ai.core.Models} as {@code openai} or {@code chatgpt}. */
public final class OpenAIModelProviders implements ModelProvider.Registry {

    @Override
    public List<ModelProvider> providers() {
        return List.of(new ModelProvider("openai", List.of("chatgpt"), "OPENAI_API_KEY", "gpt-5", OpenAIModelClient::new));
    }
}
