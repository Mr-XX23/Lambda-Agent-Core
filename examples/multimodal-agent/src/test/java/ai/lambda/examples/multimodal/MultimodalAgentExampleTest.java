package ai.lambda.examples.multimodal;

import ai.lambda.ai.anthropic.AnthropicModelClient;
import ai.lambda.ai.client.GoogleModelClient;
import ai.lambda.ai.client.OpenAIModelClient;
import ai.lambda.ai.client.ResponsesModelClient;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Modality;
import ai.lambda.ai.core.UnsupportedMediaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Checks provider selection and @file parsing without calling any API. */
class MultimodalAgentExampleTest {

    @TempDir
    Path dir;

    @Test
    void everyProviderCanBeCreated() {
        for (String provider : Providers.DEFAULTS.keySet()) {
            assertNotNull(Providers.create(provider, null, name -> "test-key"), provider);
        }
        assertInstanceOf(AnthropicModelClient.class, Providers.create("claude", null, name -> "k"));
        assertInstanceOf(GoogleModelClient.class, Providers.create("gemini", null, name -> "k"));
        assertInstanceOf(ResponsesModelClient.class, Providers.create("perplexity", null, name -> "k"));
        assertEquals("grok-4.7", ((OpenAIModelClient) Providers.create("xai", null, name -> "k")).model());
        assertTrue(Providers.create("gemini", null, name -> "k").capabilities().accepts(Modality.VIDEO));
    }

    @Test
    void missingKeysAndUnknownProvidersAreExplained() {
        var missing = assertThrows(IllegalArgumentException.class, () -> Providers.create("openai", null, name -> null));
        assertTrue(missing.getMessage().contains("OPENAI_API_KEY"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Providers.create("nope", null, name -> "k"));
        assertNotNull(Providers.create("ollama", null, name -> null), "Ollama runs locally without a key");
    }

    @Test
    void atPathsBecomeAttachments() throws Exception {
        Files.write(dir.resolve("photo.jpg"), new byte[]{1});

        Message message = MultimodalAgentExample.parse("What is in @photo.jpg exactly?", dir);

        assertEquals("What is in exactly?", message.getContent());
        assertEquals("image/jpeg", message.getMedia().get(0).mimeType());
        assertThrows(UnsupportedMediaException.class, () -> MultimodalAgentExample.parse("see @missing.png", dir));
    }
}
