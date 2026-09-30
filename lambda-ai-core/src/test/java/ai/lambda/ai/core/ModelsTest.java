package ai.lambda.ai.core;

import ai.lambda.ai.client.OpenAICompatibleModelClient;
import ai.lambda.ai.client.ResponsesModelClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ModelsTest {

    /** Every key variable set, as if the user had exported them all. */
    private static final Function<String, String> ALL_KEYS = name -> "key-from-" + name;
    private static final Function<String, String> NO_KEYS = name -> null;

    private static OpenAICompatibleModelClient openAI(ModelClient client) {
        return assertInstanceOf(OpenAICompatibleModelClient.class, client);
    }

    @Test
    void theBuiltInProvidersAreInstalled() {
        assertEquals(Set.of("openrouter", "xai", "mistral", "perplexity", "perplexity-router",
                "experiential", "ollama", "ollama-cloud"), Models.names());
    }

    @Test
    void createsTheRightClientWithTheModelAndProviderAsked() {
        Map<String, String> expectedProvider = Map.of(
                "openrouter", "OpenRouter", "xai", "xAI", "mistral", "Mistral",
                "perplexity-router", "Perplexity Router", "experiential", "Experiential Labs",
                "ollama", "Ollama", "ollama-cloud", "Ollama Cloud");
        expectedProvider.forEach((name, display) -> {
            OpenAICompatibleModelClient client = openAI(Models.create(name + ":some-model", null, ALL_KEYS));
            assertEquals("some-model", client.model(), name + ": the model must not be mixed up with the key");
            assertEquals(display, client.provider().name(), name);
        });
        assertInstanceOf(ResponsesModelClient.class, Models.create("perplexity:sonar-pro", null, ALL_KEYS));
    }

    @Test
    void readsTheKeyFromTheProvidersVariableOrUsesTheOneGiven() {
        StringBuilder asked = new StringBuilder();
        Models.create("mistral:mistral-small", null, name -> {
            asked.append(name);
            return "k";
        });
        assertEquals("MISTRAL_API_KEY", asked.toString());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Models.create("xai:grok-4.7", null, NO_KEYS));
        assertEquals("Set XAI_API_KEY to use xai, or pass the key to Models.create", missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Models.create("mistral", null, name -> " "));

        assertDoesNotThrow(() -> Models.create("xai:grok-4.7", "explicit-key", NO_KEYS), "a key given wins");
        assertDoesNotThrow(() -> Models.create("xai:grok-4.7", "explicit-key"));
    }

    @Test
    void aProviderAloneUsesItsDefaultModel() {
        assertEquals("mistral-medium-latest", openAI(Models.create("mistral", null, ALL_KEYS)).model());
        assertEquals("gemma4", openAI(Models.create("ollama", null, NO_KEYS)).model(), "Ollama needs no key");
        assertEquals("mistral-medium-latest", openAI(Models.create("mistral:", null, ALL_KEYS)).model());
    }

    @Test
    void onlyTheFirstColonSeparatesTheProvider() {
        assertEquals("gemma3:4b", openAI(Models.create("ollama:gemma3:4b", null, NO_KEYS)).model());
        assertEquals("meta-llama/llama-4:free", openAI(Models.create("openrouter:meta-llama/llama-4:free", null, ALL_KEYS)).model());
    }

    @Test
    void namesAndAliasesIgnoreCase() {
        assertEquals("Mistral", openAI(Models.create("MISTRAL:m", null, ALL_KEYS)).provider().name());
        assertEquals("xAI", openAI(Models.create("grok:grok-4.7", null, ALL_KEYS)).provider().name());
        assertEquals("Experiential Labs", openAI(Models.create("experiential-labs:m", null, ALL_KEYS)).provider().name());
        assertEquals("OpenRouter", openAI(Models.create(" OpenRouter : x/y ", null, ALL_KEYS)).provider().name());
        assertEquals("xai", Models.provider("GROK").name());
    }

    @Test
    void explainsWhatToInstallOrWhatExists() {
        IllegalArgumentException claude = assertThrows(IllegalArgumentException.class,
                () -> Models.create("claude:claude-opus-5-5", null, ALL_KEYS));
        assertEquals("The 'claude' provider is not installed. Add the lambda-ai-core-anthropic dependency to use it.",
                claude.getMessage());

        IllegalArgumentException openai = assertThrows(IllegalArgumentException.class,
                () -> Models.create("ChatGPT:gpt-5", null, ALL_KEYS));
        assertEquals("The 'chatgpt' provider is not installed. Add the lambda-ai-core-openai dependency to use it.",
                openai.getMessage());

        IllegalArgumentException gemini = assertThrows(IllegalArgumentException.class,
                () -> Models.create("google:gemini-3.1-flash", null, ALL_KEYS));
        assertEquals("The 'google' provider is not installed. Add the lambda-ai-core-gemini dependency to use it.",
                gemini.getMessage());

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> Models.create("nope:model", null, ALL_KEYS));
        assertTrue(unknown.getMessage().startsWith("Unknown provider 'nope'. Installed: [experiential, mistral,"),
                unknown.getMessage());

        assertThrows(IllegalArgumentException.class, () -> Models.create(" ", null, ALL_KEYS));
        assertThrows(IllegalArgumentException.class, () -> Models.create(null, null, ALL_KEYS));
    }

    @Test
    void providerRecordsValidateAndCopy() {
        ModelProvider provider = new ModelProvider("x", null, null, "m", (key, model) -> null);
        assertEquals(java.util.List.of(), provider.aliases());
        assertFalse(provider.requiresApiKey());
        assertThrows(NullPointerException.class, () -> new ModelProvider(null, null, null, "m", (k, m) -> null));
        assertThrows(NullPointerException.class, () -> new ModelProvider("x", null, null, null, (k, m) -> null));
    }
}
