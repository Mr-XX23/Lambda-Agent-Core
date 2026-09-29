package ai.lambda.examples.multiAgent;

import ai.lambda.agent.core.Agent;
import ai.lambda.agent.core.AgentConfig;
import ai.lambda.agent.core.InMemorySessionStore;
import ai.lambda.agent.core.Subagent;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps the example's agent definitions valid, so a typo fails the build instead of the demo. */
class MultiAgentExampleTest {

    private static final ModelClient UNUSED = new ModelClient() {
        public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
            throw new UnsupportedOperationException();
        }

        public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void agentDefinitionsLoadAndOnlyUseAvailableTools() {
        AgentConfig config = MultiAgentExample.config(UNUSED);

        assertEquals(List.of("code-reviewer", "doc-writer"),
                config.getSubagents().all().stream().map(Subagent::name).toList());
        assertTrue(config.getSubagents().isSelfCloning());
        // Building the agent checks that every subagent's tools exist.
        assertDoesNotThrow(() -> new Agent(config, new InMemorySessionStore()));
    }
}
