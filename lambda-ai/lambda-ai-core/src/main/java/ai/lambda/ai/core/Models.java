package ai.lambda.ai.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;

/**
 * Creates a model client from a {@code "provider:model"} string, for whichever providers are
 * installed. The key is read from the provider's environment variable unless given.
 *
 * <pre>
 * ModelClient gemini = Models.create("gemini:gemini-3.1-flash");   // needs lambda-ai-gemini
 * ModelClient claude = Models.create("anthropic:claude-opus-5-5");  // needs lambda-ai-anthropic
 * ModelClient local  = Models.create("ollama:gemma3:4b");           // needs lambda-ai-others; no key
 * ModelClient any    = Models.create(System.getenv("LAMBDA_MODEL")); // the model chosen in configuration
 * </pre>
 *
 * Each provider module adds its providers when it is installed: {@code lambda-ai-openai} adds
 * {@code openai} ({@code chatgpt}), {@code lambda-ai-gemini} adds {@code gemini} ({@code google}),
 * {@code lambda-ai-anthropic} adds {@code anthropic} ({@code claude}), each through the provider's
 * official SDK, and {@code lambda-ai-others} adds {@code openrouter}, {@code xai}, {@code mistral},
 * {@code perplexity}, {@code perplexity-router}, {@code experiential}, {@code ollama} and
 * {@code ollama-cloud}. Only the first {@code ':'} separates provider and model, so model names may
 * contain colons. A name without a model, such as {@code "gemini"}, uses that provider's default.
 */
public final class Models {

    /** Providers that live in their own module, and that module's name, for a helpful error. */
    private static final Map<String, String> SEPARATE_MODULES = Map.ofEntries(
            Map.entry("anthropic", "lambda-ai-anthropic"), Map.entry("claude", "lambda-ai-anthropic"),
            Map.entry("openai", "lambda-ai-openai"), Map.entry("chatgpt", "lambda-ai-openai"),
            Map.entry("gemini", "lambda-ai-gemini"), Map.entry("google", "lambda-ai-gemini"),
            Map.entry("openrouter", "lambda-ai-others"), Map.entry("xai", "lambda-ai-others"),
            Map.entry("grok", "lambda-ai-others"), Map.entry("mistral", "lambda-ai-others"),
            Map.entry("perplexity", "lambda-ai-others"), Map.entry("perplexity-router", "lambda-ai-others"),
            Map.entry("experiential", "lambda-ai-others"), Map.entry("experiential-labs", "lambda-ai-others"),
            Map.entry("ollama", "lambda-ai-others"), Map.entry("ollama-cloud", "lambda-ai-others"));

    private static volatile Map<String, ModelProvider> installed;

    private Models() {
    }

    /** A client for {@code "provider:model"} (or just {@code "provider"}), with the key from the environment. */
    public static ModelClient create(String spec) {
        return create(spec, null, System::getenv);
    }

    /** A client for {@code "provider:model"} (or just {@code "provider"}) with this API key. */
    public static ModelClient create(String spec, String apiKey) {
        return create(spec, apiKey, System::getenv);
    }

    static ModelClient create(String spec, String apiKey, Function<String, String> environment) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Give a model as \"provider:model\", for example \"gemini:gemini-3.1-flash\"");
        }
        int colon = spec.indexOf(':');
        String name = (colon < 0 ? spec : spec.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
        String model = colon < 0 ? "" : spec.substring(colon + 1).trim();
        ModelProvider provider = provider(name);
        String key = apiKey;
        if (key == null && provider.requiresApiKey()) {
            key = environment.apply(provider.apiKeyVariable());
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Set " + provider.apiKeyVariable() + " to use " + provider.name()
                        + ", or pass the key to Models.create");
            }
        }
        return provider.factory().apply(key, model.isEmpty() ? provider.defaultModel() : model);
    }

    /** The installed provider with this name or alias. */
    public static ModelProvider provider(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        ModelProvider provider = installed().get(key);
        if (provider != null) return provider;
        String module = SEPARATE_MODULES.get(key);
        if (module != null) {
            throw new IllegalArgumentException("The '" + key + "' provider is not installed. Add the " + module
                    + " dependency to use it.");
        }
        throw new IllegalArgumentException("Unknown provider '" + name + "'. Installed: " + names());
    }

    /** The names of the installed providers (without aliases). */
    public static Set<String> names() {
        Set<String> names = new java.util.TreeSet<>();
        for (ModelProvider provider : installed().values()) names.add(provider.name());
        return names;
    }

    private static Map<String, ModelProvider> installed() {
        Map<String, ModelProvider> found = installed;
        if (found == null) {
            synchronized (Models.class) {
                found = installed;
                if (found == null) installed = found = discover();
            }
        }
        return found;
    }

    private static Map<String, ModelProvider> discover() {
        Map<String, ModelProvider> byName = new LinkedHashMap<>();
        for (ModelProvider.Registry registry : ServiceLoader.load(ModelProvider.Registry.class, Models.class.getClassLoader())) {
            for (ModelProvider provider : registry.providers()) {
                register(byName, provider.name(), provider);
                for (String alias : provider.aliases()) register(byName, alias, provider);
            }
        }
        return Map.copyOf(byName);
    }

    private static void register(Map<String, ModelProvider> byName, String name, ModelProvider provider) {
        ModelProvider previous = byName.putIfAbsent(name.toLowerCase(Locale.ROOT), provider);
        if (previous != null && previous != provider) {
            throw new IllegalStateException("Two installed modules both provide '" + name + "'");
        }
    }
}
