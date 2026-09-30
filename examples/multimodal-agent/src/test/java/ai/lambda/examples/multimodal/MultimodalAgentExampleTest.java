package ai.lambda.examples.multimodal;

import ai.lambda.ai.anthropic.AnthropicModelClient;
import ai.lambda.ai.gemini.GeminiModelClient;
import ai.lambda.ai.client.OpenAICompatibleModelClient;
import ai.lambda.ai.client.ResponsesModelClient;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Modality;
import ai.lambda.ai.core.Models;
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
    void everyInstalledProviderCanBeCreated() {
        // This example depends on lambda-ai-core-anthropic, so Claude is installed next to the built-in providers.
        assertTrue(Models.names().contains("anthropic"), Models.names().toString());
        for (String provider : Models.names()) {
            assertNotNull(Models.create(provider, "test-key"), provider);
        }
        assertInstanceOf(AnthropicModelClient.class, Models.create("claude", "k"));
        assertEquals("claude-sonnet-5-5", ((AnthropicModelClient) Models.create("claude:claude-sonnet-5-5", "k")).model());
        assertInstanceOf(GeminiModelClient.class, Models.create("gemini", "k"));
        assertInstanceOf(ResponsesModelClient.class, Models.create("perplexity", "k"));
        assertEquals("grok-4.7", ((OpenAICompatibleModelClient) Models.create("xai", "k")).model());
        assertTrue(Models.create("gemini", "k").capabilities().accepts(Modality.VIDEO));
    }

    @Test
    void unknownProvidersAreExplained() {
        var unknown = assertThrows(IllegalArgumentException.class, () -> Models.create("nope", "k"));
        assertTrue(unknown.getMessage().contains("anthropic"), unknown.getMessage());
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
