package ai.lambda.ai.anthropic;

import ai.lambda.ai.core.Models;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicModelProvidersTest {

    @Test
    void installingThisModuleMakesClaudeAvailable() {
        assertTrue(Models.names().contains("anthropic"), Models.names().toString());
        assertTrue(Models.names().contains("mistral"), "the built-in providers are still there");

        AnthropicModelClient claude = assertInstanceOf(AnthropicModelClient.class, Models.create("claude:claude-sonnet-5-5", "k"));
        assertEquals("claude-sonnet-5-5", claude.model());
        assertEquals(AnthropicModelClient.DEFAULT_MODEL, ((AnthropicModelClient) Models.create("anthropic", "k")).model());
        assertEquals("ANTHROPIC_API_KEY", Models.provider("claude").apiKeyVariable());
    }
}
