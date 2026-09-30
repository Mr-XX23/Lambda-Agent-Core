package ai.lambda.ai.client;

import ai.lambda.ai.core.ModelProvider;

import java.util.List;

/** The providers {@code lambda-ai-core} supports by itself, for {@link ai.lambda.ai.core.Models}. */
public final class BuiltInModelProviders implements ModelProvider.Registry {

    @Override
    public List<ModelProvider> providers() {
        return List.of(
                new ModelProvider("openai", List.of("chatgpt"), "OPENAI_API_KEY", "gpt-5", OpenAIModelClient::openAI),
                new ModelProvider("gemini", List.of("google"), "GEMINI_API_KEY", "gemini-3.1-flash",
                        GoogleModelClient::new),
                new ModelProvider("openrouter", List.of(), "OPENROUTER_API_KEY", "google/gemini-3.1-flash",
                        OpenAIModelClient::openRouter),
                new ModelProvider("xai", List.of("grok"), "XAI_API_KEY", "grok-4.7", OpenAIModelClient::xai),
                new ModelProvider("mistral", List.of(), "MISTRAL_API_KEY", "mistral-medium-latest",
                        OpenAIModelClient::mistral),
                new ModelProvider("perplexity", List.of(), "PERPLEXITY_API_KEY", "perplexity/sonar",
                        ResponsesModelClient::perplexity),
                new ModelProvider("perplexity-router", List.of(), "PERPLEXITY_API_KEY", "sonar",
                        OpenAIModelClient::perplexityRouter),
                new ModelProvider("experiential", List.of("experiential-labs"), "EXPLABS_API_KEY", "qwen3.8-27b",
                        OpenAIModelClient::experientialLabs),
                new ModelProvider("ollama", List.of(), null, "gemma4", (key, model) -> OpenAIModelClient.ollama(model)),
                new ModelProvider("ollama-cloud", List.of(), "OLLAMA_API_KEY", "gpt-oss:120b",
                        OpenAIModelClient::ollamaCloud));
    }
}
