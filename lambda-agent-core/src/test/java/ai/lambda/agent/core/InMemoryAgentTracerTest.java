package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAgentTracerTest {
    @Test
    void capturesRunModelAndIterationEvents() {
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return new ChatResponse(new Message(Role.ASSISTANT, "done", null), List.of());
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                return chat(messages, tools);
            }
        };
        InMemoryAgentTracer tracer = new InMemoryAgentTracer();
        Agent agent = new Agent(new AgentConfig("system", model), new InMemorySessionStore());
        agent.addListener(tracer);

        AgentResult result = agent.run("session-1", "hello");

        assertTrue(tracer.snapshot().stream().anyMatch(e -> e.type() == TraceEventType.RUN_STARTED));
        assertTrue(tracer.snapshot().stream().anyMatch(e -> e.type() == TraceEventType.ITERATION_STARTED));
        TraceEvent modelEvent = tracer.snapshot().stream()
                .filter(e -> e.type() == TraceEventType.MODEL_COMPLETED).findFirst().orElseThrow();
        assertEquals(result.getRunId(), modelEvent.runId());
        assertTrue(modelEvent.durationMillis() >= 0);
        assertTrue(tracer.snapshot().stream().anyMatch(e -> e.type() == TraceEventType.RUN_COMPLETED));
    }
}
