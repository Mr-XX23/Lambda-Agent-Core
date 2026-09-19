package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {

    @Test
    void executesToolAndPreservesToolCallOnAssistantMessage() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = new ModelClient() {
            @Override
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return response(messages);
            }

            @Override
            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                calls.incrementAndGet();
                if (calls.get() == 1) {
                    ToolCall toolCall = new ToolCall("call-1", "echo", "{\"text\":\"hello\"}");
                    return new ChatResponse(new Message(Role.ASSISTANT, "", null), List.of(toolCall));
                }
                return new ChatResponse(new Message(Role.ASSISTANT, "done", null), List.of());
            }

            private ChatResponse response(List<Message> messages) {
                return new ChatResponse(new Message(Role.ASSISTANT, "done", null), List.of());
            }
        };

        AgentTool echo = new AgentTool() {
            public String getName() { return "echo"; }
            public String getDescription() { return "Echoes text"; }
            public String getJsonSchema() { return "{\"type\":\"object\"}"; }
            public ToolResult execute(ToolInvocationContext context) {
                return ToolResult.of("echoed");
            }
        };

        Agent agent = new Agent(new AgentConfig("system", model, List.of(echo), 3),
                new InMemorySessionStore());
        AgentResult result = agent.run("session", "hello");

        assertEquals("done", result.getFinalText());
        assertEquals(2, calls.get());
        assertEquals(List.of("SYSTEM", "USER", "ASSISTANT", "TOOL", "ASSISTANT"),
                result.getSession().getMessages().stream().map(m -> m.getRole().name()).toList());
        assertEquals("call-1", result.getSession().getMessages().get(2).getToolCalls().get(0).getId());
    }

    @Test
    void returnsSafetyStopAfterMaxIterations() {
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return new ChatResponse(new Message(Role.ASSISTANT, "", null),
                        List.of(new ToolCall("call", "missing", "{}")));
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                return chat(messages, tools);
            }
        };

        Agent agent = new Agent(new AgentConfig("system", model, List.of(), 2),
                new InMemorySessionStore());

        AgentResult result = agent.run("session", "hello");

        assertEquals("[lambda-agent-core] Stopped after max iterations.", result.getFinalText());
        assertEquals(2, result.getSession().getMessages().stream()
                .filter(message -> message.getRole() == Role.ASSISTANT).count());
    }

    @Test
    void supportsCancellationBeforeModelCall() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                fail("Model must not be called after cancellation");
                return null;
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                return chat(messages, tools);
            }
        };
        Agent agent = new Agent(new AgentConfig("system", model), new InMemorySessionStore());

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> agent.run("session", "hello", token));
    }

    @Test
    void reportsRunMetadataToResultAndListeners() {
        List<String> runIds = new java.util.ArrayList<>();
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return new ChatResponse(new Message(Role.ASSISTANT, "done", null), List.of());
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                return chat(messages, tools);
            }
        };
        Agent agent = new Agent(new AgentConfig("system", model), new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            @Override
            public void onRunStart(String runId, String sessionId) {
                runIds.add(runId);
            }
        });

        AgentResult result = agent.run("session", "hello");

        assertEquals(1, runIds.size());
        assertEquals(runIds.get(0), result.getRunId());
        assertEquals(1, result.getIterations());
    }

    @Test
    void rejectsInvalidToolDefinitionsAtConstruction() {
        AgentTool invalid = new AgentTool() {
            public String getName() { return "invalid"; }
            public String getDescription() { return "invalid"; }
            public String getJsonSchema() { return "{not-json"; }
            public ToolResult execute(ToolInvocationContext context) { return ToolResult.of(""); }
        };
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) { return null; }
            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) { return null; }
        };

        assertThrows(IllegalArgumentException.class,
                () -> new Agent(new AgentConfig("system", model, List.of(invalid), 1),
                        new InMemorySessionStore()));
    }
}
