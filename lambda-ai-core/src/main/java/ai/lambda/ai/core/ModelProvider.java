package ai.lambda.ai.core;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * One provider that {@link Models#create} can build clients for.
 *
 * @param name           the name used in {@code "name:model"}, such as {@code "gemini"}
 * @param aliases        other accepted names, such as {@code "claude"} for {@code "anthropic"}
 * @param apiKeyVariable the environment variable holding the API key, or null if none is needed
 * @param defaultModel   the model used when none is given
 * @param factory        builds a client from an API key (null when none is needed) and a model name,
 *                       in that order, like the clients' own constructors
 */
public record ModelProvider(String name, List<String> aliases, String apiKeyVariable, String defaultModel,
                            BiFunction<String, String, ModelClient> factory) {

    public ModelProvider {
        Objects.requireNonNull(name, "name must not be null");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
    }

    public boolean requiresApiKey() {
        return apiKeyVariable != null;
    }

    /**
     * Registers providers with {@link Models}. A module lists its implementation in
     * {@code META-INF/services/ai.lambda.ai.core.ModelProvider$Registry}, so installing the module
     * is enough to make its providers available.
     */
    public interface Registry {
        List<ModelProvider> providers();
    }
}
