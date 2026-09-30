package ai.lambda.ai.client;

import ai.lambda.ai.core.ModelProvider;

import java.util.List;

/** The providers of {@code lambda-ai-others} (no official Java SDK), for {@link ai.lambda.ai.core.Models}. */
public final class OtherModelProviders implements ModelProvider.Registry {

    @Override
    public List<ModelProvider> providers() {
        return List.of(
                new ModelProvider("openrouter", List.of(), "OPENROUTER_API_KEY", "google/gemini-3.1-flash",
                        OpenAICompatibleModelClient::openRouter),
                new ModelProvider("xai", List.of("grok"), "XAI_API_KEY", "grok-4.7", OpenAICompatibleModelClient::xai),
                new ModelProvider("mistral", List.of(), "MISTRAL_API_KEY", "mistral-medium-latest",
                        OpenAICompatibleModelClient::mistral),
                new ModelProvider("perplexity", List.of(), "PERPLEXITY_API_KEY", "perplexity/sonar",
                        ResponsesModelClient::perplexity),
                new ModelProvider("perplexity-router", List.of(), "PERPLEXITY_API_KEY", "sonar",
                        OpenAICompatibleModelClient::perplexityRouter),
                new ModelProvider("experiential", List.of("experiential-labs"), "EXPLABS_API_KEY", "qwen3.8-27b",
                        OpenAICompatibleModelClient::experientialLabs),
                new ModelProvider("ollama", List.of(), null, "gemma4", (key, model) -> OpenAICompatibleModelClient.ollama(model)),
                new ModelProvider("ollama-cloud", List.of(), "OLLAMA_API_KEY", "gpt-oss:120b",
                        OpenAICompatibleModelClient::ollamaCloud));
    }
}
