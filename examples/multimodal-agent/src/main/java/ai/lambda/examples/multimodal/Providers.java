package ai.lambda.examples.multimodal;

import ai.lambda.ai.anthropic.AnthropicModelClient;
import ai.lambda.ai.client.GoogleModelClient;
import ai.lambda.ai.client.OpenAIModelClient;
import ai.lambda.ai.client.ResponsesModelClient;
import ai.lambda.ai.core.ModelClient;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Picks a model client by provider name, so the example runs with any supported provider. */
final class Providers {

    /** provider -> {environment variable holding the key, default model} */
    static final Map<String, String[]> DEFAULTS = Map.of(
            "openai", new String[]{"OPENAI_API_KEY", "gpt-5"},
            "claude", new String[]{"ANTHROPIC_API_KEY", "claude-opus-5-5"},
            "gemini", new String[]{"GEMINI_API_KEY", "gemini-3.8-flash"},
            "openrouter", new String[]{"OPENROUTER_API_KEY", "google/gemini-3.8-flash"},
            "xai", new String[]{"XAI_API_KEY", "grok-4.7"},
            "mistral", new String[]{"MISTRAL_API_KEY", "mistral-medium-latest"},
            "perplexity", new String[]{"PERPLEXITY_API_KEY", "perplexity/sonar"},
            "experiential", new String[]{"EXPLABS_API_KEY", "qwen3.8-27b"},
            "ollama", new String[]{null, "gemma4"});

    private Providers() {
    }

    /**
     * @param provider one of {@link #DEFAULTS}' keys
     * @param model    the model, or null for the provider's default
     * @param env      how to read environment variables (System::getenv outside tests)
     */
    static ModelClient create(String provider, String model, Function<String, String> env) {
        String name = provider.toLowerCase(Locale.ROOT);
        String[] defaults = DEFAULTS.get(name);
        if (defaults == null) {
            throw new IllegalArgumentException("Unknown provider '" + provider + "'. Choose one of " + DEFAULTS.keySet());
        }
        String key = defaults[0] == null ? null : env.apply(defaults[0]);
        if (defaults[0] != null && (key == null || key.isBlank())) {
            throw new IllegalArgumentException("Set " + defaults[0] + " to use " + name);
        }
        String m = model == null || model.isBlank() ? defaults[1] : model;
        return switch (name) {
            case "openai" -> OpenAIModelClient.openAI(key, m);
            case "claude" -> new AnthropicModelClient(key, m);
            case "gemini" -> new GoogleModelClient(key, m);
            case "openrouter" -> OpenAIModelClient.openRouter(key, m);
            case "xai" -> OpenAIModelClient.xai(key, m);
            case "mistral" -> OpenAIModelClient.mistral(key, m);
            case "perplexity" -> ResponsesModelClient.perplexity(key, m);
            case "experiential" -> OpenAIModelClient.experientialLabs(key, m);
            default -> OpenAIModelClient.ollama(m);
        };
    }
}
