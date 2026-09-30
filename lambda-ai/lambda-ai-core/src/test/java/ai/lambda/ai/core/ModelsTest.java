package ai.lambda.ai.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Models.create with only the test provider installed (see TestModelProviders). */
class ModelsTest {

    private static final Function<String, String> ALL_KEYS = name -> "key-from-" + name;
    private static final Function<String, String> NO_KEYS = name -> null;

    private static TestModelProviders.FakeClient fake(ModelClient client) {
        return assertInstanceOf(TestModelProviders.FakeClient.class, client);
    }

    @Test
    void findsTheProvidersOfInstalledModules() {
        assertEquals(Set.of("fake", "local"), Models.names());
        assertEquals("fake", Models.provider("PRETEND").name());
    }

    @Test
    void passesTheKeyAndModelInTheRightOrder() {
        TestModelProviders.FakeClient client = fake(Models.create("fake:model-x", null, ALL_KEYS));
        assertEquals("model-x", client.model());
        assertEquals("key-from-FAKE_API_KEY", client.apiKey());
    }

    @Test
    void readsTheKeyFromTheVariableOrUsesTheOneGiven() {
        StringBuilder asked = new StringBuilder();
        Models.create("fake:m", null, name -> {
            asked.append(name);
            return "k";
        });
        assertEquals("FAKE_API_KEY", asked.toString());

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Models.create("fake:m", null, NO_KEYS));
        assertEquals("Set FAKE_API_KEY to use fake, or pass the key to Models.create", missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Models.create("fake", null, name -> " "));

        assertEquals("explicit", fake(Models.create("fake:m", "explicit", NO_KEYS)).apiKey(), "a key given wins");
        assertEquals("explicit", fake(Models.create("fake:m", "explicit")).apiKey());
        assertNull(fake(Models.create("local", null, NO_KEYS)).apiKey(), "a provider without a key variable needs none");
    }

    @Test
    void aProviderAloneUsesItsDefaultModel() {
        assertEquals("fake-default", fake(Models.create("fake", null, ALL_KEYS)).model());
        assertEquals("fake-default", fake(Models.create("fake:", null, ALL_KEYS)).model());
    }

    @Test
    void onlyTheFirstColonSeparatesTheProviderAndCaseIsIgnored() {
        assertEquals("gemma3:4b", fake(Models.create("local:gemma3:4b", null, NO_KEYS)).model());
        assertEquals("org/model:free", fake(Models.create(" Pretend : org/model:free ", null, ALL_KEYS)).model());
    }

    @Test
    void explainsWhichModuleToAdd() {
        for (String[] expected : List.of(
                new String[]{"claude", "lambda-ai-anthropic"}, new String[]{"openai", "lambda-ai-openai"},
                new String[]{"google", "lambda-ai-gemini"}, new String[]{"mistral", "lambda-ai-others"},
                new String[]{"ollama", "lambda-ai-others"}, new String[]{"grok", "lambda-ai-others"})) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Models.create(expected[0] + ":m", null, ALL_KEYS));
            assertEquals("The '" + expected[0] + "' provider is not installed. Add the " + expected[1]
                    + " dependency to use it.", e.getMessage());
        }

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> Models.create("nope:model", null, ALL_KEYS));
        assertEquals("Unknown provider 'nope'. Installed: [fake, local]", unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Models.create(" ", null, ALL_KEYS));
        assertThrows(IllegalArgumentException.class, () -> Models.create(null, null, ALL_KEYS));
    }

    @Test
    void providerRecordsValidateAndCopy() {
        ModelProvider provider = new ModelProvider("x", null, null, "m", (key, model) -> null);
        assertEquals(List.of(), provider.aliases());
        assertFalse(provider.requiresApiKey());
        assertThrows(NullPointerException.class, () -> new ModelProvider(null, null, null, "m", (k, m) -> null));
        assertThrows(NullPointerException.class, () -> new ModelProvider("x", null, null, null, (k, m) -> null));
    }
}
