package ai.lambda.agent.core;

import ai.lambda.agent.prebuilt.EchoTool;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {

    private static final String SESSION = "s1";

    /** A tool that always throws, for testing error handling. */
    private static final class FailingTool implements AgentTool {
        public String getName() { return "fail"; }
        public String getDescription() { return "Always fails."; }
        public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        public ToolResult execute(ToolInvocationContext context) { throw new IllegalStateException("boom"); }
    }

    private static Agent agent(FakeModelClient model, List<AgentTool> tools, ToolErrorStrategy errors) {
        var config = new AgentConfig("You are a test.", model, tools, 5, errors, null);
        return new Agent(config, new InMemorySessionStore());
    }

    private static List<Role> roles(AgentResult result) {
        return result.getSession().getMessages().stream().map(Message::getRole).toList();
    }

    @Test
    void returnsTextWhenModelAnswersDirectly() {
        var model = new FakeModelClient().replyText("Hello!");

        AgentResult result = agent(model, List.of(), null).run(SESSION, "hi");

        assertEquals("Hello!", result.getFinalText());
        assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT), roles(result));
        assertEquals(1, model.requests.size());
    }

    @Test
    void runsRequestedToolAndKeepsTheCallInHistory() {
        var model = new FakeModelClient()
                .replyToolCall("c1", "echo", "{\"text\":\"hi\"}")
                .replyText("done");

        AgentResult result = agent(model, List.of(new EchoTool()), null).run(SESSION, "echo hi");

        assertEquals("done", result.getFinalText());
        assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), roles(result));

        List<Message> messages = result.getSession().getMessages();
        Message toolRequest = messages.get(2);
        assertEquals(1, toolRequest.getToolCalls().size(), "assistant message must remember which tool it asked for");
        assertEquals("c1", toolRequest.getToolCalls().get(0).getId());

        Message toolResult = messages.get(3);
        assertEquals("c1", toolResult.getToolCallId());
        assertEquals("echo", toolResult.getToolCallName());
        assertEquals("[echo] hi", toolResult.getContent());

        // The second model call must see the call followed by its result.
        List<Message> secondRequest = model.requests.get(1);
        assertEquals(1, secondRequest.get(2).getToolCalls().size());
        assertEquals(Role.TOOL, secondRequest.get(3).getRole());
    }

    @Test
    void unknownToolIsReportedToModelWithItsName() {
        var model = new FakeModelClient()
                .replyToolCall("c1", "missing_tool", "{}")
                .replyText("sorry");

        AgentResult result = agent(model, List.of(), null).run(SESSION, "do it");

        Message toolResult = result.getSession().getMessages().get(3);
        assertEquals(Role.TOOL, toolResult.getRole());
        assertEquals("missing_tool", toolResult.getToolCallName());
        assertTrue(toolResult.getContent().contains("not available"), toolResult.getContent());
        assertEquals("sorry", result.getFinalText());
    }

    @Test
    void toolFailureIsSentToModelByDefault() {
        var model = new FakeModelClient()
                .replyToolCall("c1", "fail", "{}")
                .replyText("recovered");

        AgentResult result = agent(model, List.of(new FailingTool()), null).run(SESSION, "try");

        Message toolResult = result.getSession().getMessages().get(3);
        assertEquals("fail", toolResult.getToolCallName());
        assertTrue(toolResult.getContent().contains("boom"), toolResult.getContent());
        assertEquals("recovered", result.getFinalText());
    }

    @Test
    void toolFailureAbortsRunWithThrowStrategy() {
        var model = new FakeModelClient().replyToolCall("c1", "fail", "{}");
        Agent agent = agent(model, List.of(new FailingTool()), ToolErrorStrategy.THROW);

        RuntimeException e = assertThrows(RuntimeException.class, () -> agent.run(SESSION, "try"));

        assertEquals("boom", rootCause(e).getMessage());
    }

    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
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
    void mediaIsSentToTheModelAndKeptInTheSession() {
        var model = new FakeModelClient().replyText("a cat");
        var photo = ai.lambda.ai.core.Media.of(new byte[]{1, 2, 3}, "image/jpeg");

        AgentResult result = agent(model, List.of(), null).run(SESSION, "What is this?", photo);

        Message sent = model.requests.get(0).get(1);
        assertEquals("What is this?", sent.getContent());
        assertEquals(List.of(photo), sent.getMedia());
        assertEquals(List.of(photo), result.getSession().getMessages().get(1).getMedia());
    }

    @Test
    void runRejectsNonUserMessages() {
        var agent = agent(new FakeModelClient(), List.of(), null);
        assertThrows(IllegalArgumentException.class,
                () -> agent.run(SESSION, new Message(Role.ASSISTANT, "hi", null)));
    }

    @Test
    void contextStrategyTrimsWhatModelSeesButNotTheSession() {
        var model = new FakeModelClient().replyText("a").replyText("b");
        var config = new AgentConfig("sys", model, List.of(), 5, null, new SlidingWindowStrategy(2));
        var agent = new Agent(config, new InMemorySessionStore());

        agent.run(SESSION, "first");
        AgentResult result = agent.run(SESSION, "second");

        List<Message> seen = model.requests.get(1);
        assertEquals(2, seen.size());
        assertEquals(Role.SYSTEM, seen.get(0).getRole());
        assertEquals("second", seen.get(1).getContent());

        assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), roles(result),
                "full history is kept, and the system prompt is added only once");
    }

    @Test
    void withContextStrategyKeepsTheRestOfTheConfig() {
        var model = new FakeModelClient();
        var config = new AgentConfig("sys", model, List.of(), 3, ToolErrorStrategy.THROW,
                Duration.ofSeconds(7), 99, RetryPolicy.none());

        AgentConfig trimmed = config.withContextStrategy(new SlidingWindowStrategy(4));

        assertInstanceOf(SlidingWindowStrategy.class, trimmed.getContextStrategy());
        assertEquals(ToolErrorStrategy.THROW, trimmed.getToolErrorStrategy());
        assertEquals(Duration.ofSeconds(7), trimmed.getRunTimeout());
        assertEquals(99, trimmed.getMaxToolArgumentLength());
        assertInstanceOf(NoOpStrategy.class, config.getContextStrategy(), "the original is unchanged");
    }

    @Test
    void largeToolResultKeepsStartAndEndInHistoryButListenersGetAllOfIt() {
        AgentTool bigTool = new AgentTool() {
            public String getName() { return "big"; }
            public String getDescription() { return "Returns a lot of text."; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolPolicy getPolicy() { return new ToolPolicy(false, Duration.ofSeconds(5), 100); }
            public ToolResult execute(ToolInvocationContext context) {
                return ToolResult.of("S".repeat(500) + "E".repeat(500));
            }
        };
        var model = new FakeModelClient().replyToolCall("c1", "big", "{}").replyText("done");
        var agent = new Agent(new AgentConfig("sys", model, List.of(bigTool), 5), new InMemorySessionStore());
        int[] seenByListener = {0};
        agent.addListener(new AgentEventListener() {
            @Override
            public void onToolEnd(ToolCall call, ToolResult result) {
                seenByListener[0] = result.getContent().length();
            }
        });

        AgentResult result = agent.run(SESSION, "go");

        String stored = result.getSession().getMessages().get(3).getContent();
        assertTrue(stored.startsWith("SSS") && stored.endsWith("EEE"), stored);
        assertTrue(stored.contains("Result truncated: 900 characters omitted."), stored);
        assertEquals(1_000, seenByListener[0]);
    }

    @Test
    void trimmedHistoryStaysValidDuringALongToolLoop() {
        var model = new FakeModelClient();
        for (int i = 0; i < 4; i++) model.replyToolCall("c" + i, "echo", "{\"text\":\"step\"}");
        model.replyText("finished");
        var config = new AgentConfig("sys", model, List.of(new EchoTool()), 8, null, new SlidingWindowStrategy(4));

        AgentResult result = new Agent(config, new InMemorySessionStore()).run(SESSION, "do many steps");

        assertEquals("finished", result.getFinalText());
        for (List<Message> request : model.requests) {
            ContextStrategyTest.assertValidForModels(request);
        }
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

    @Test
    void blocksUnapprovedToolCalls() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            public String getName() { return "write"; }
            public String getDescription() { return "writes"; }
            public String getJsonSchema() { return "{\"type\":\"object\"}"; }
            public ToolPolicy getPolicy() { return new ToolPolicy(true, Duration.ofSeconds(1)); }
            public ToolResult execute(ToolInvocationContext context) {
                executions.incrementAndGet();
                return ToolResult.of("written");
            }
        };
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return new ChatResponse(new Message(Role.ASSISTANT, "", null),
                        List.of(new ToolCall("call", "write", "{}")));
            }
            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) { return chat(messages, tools); }
        };
        AgentConfig config = new AgentConfig("system", model, List.of(tool), 1,
                ToolErrorStrategy.SEND_TO_MODEL, Duration.ofSeconds(1), 1024,
                RetryPolicy.none(), (session, call) -> false);

        AgentResult result = new Agent(config, new InMemorySessionStore()).run("session", "write");

        assertEquals(0, executions.get());
        assertTrue(result.getSession().getMessages().stream()
                .anyMatch(message -> message.getContent().contains("not approved")));
    }

    @Test
    void validatesArgumentsAndTruncatesLargeResults() {
        AgentTool tool = new AgentTool() {
            public String getName() { return "bounded"; }
            public String getDescription() { return "bounded"; }
            public String getJsonSchema() { return "{\"type\":\"object\"}"; }
            public ToolPolicy getPolicy() { return new ToolPolicy(false, Duration.ofSeconds(1), 5); }
            public ToolArgumentValidator getArgumentValidator() {
                return arguments -> {
                    if (!arguments.contains("\"ok\"")) throw new IllegalArgumentException("missing ok");
                };
            }
            public ToolResult execute(ToolInvocationContext context) {
                return ToolResult.of("123456789");
            }
        };
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return new ChatResponse(new Message(Role.ASSISTANT, "done", null), List.of());
            }
            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                if (calls.incrementAndGet() == 1) {
                    return new ChatResponse(new Message(Role.ASSISTANT, "", null),
                            List.of(new ToolCall("call", "bounded", "{\"ok\":true}")));
                }
                return chat(messages, tools);
            }
        };

        AgentResult result = new Agent(new AgentConfig("system", model, List.of(tool), 2),
                new InMemorySessionStore()).run("session", "run");

        assertTrue(result.getSession().getMessages().stream()
                .anyMatch(message -> message.getContent().contains("Result truncated")));
    }

    @Test
    void retriesTransientModelFailureAndNotifiesListener() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return new ChatResponse(new Message(Role.ASSISTANT, "done", null), List.of());
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException("temporary");
                }
                return chat(messages, tools);
            }
        };
        AgentConfig config = new AgentConfig("system", model, List.of(), 1,
                ToolErrorStrategy.SEND_TO_MODEL, Duration.ofSeconds(1), 1024,
                RetryPolicy.exponential(2, Duration.ZERO));
        Agent agent = new Agent(config, new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            @Override
            public void onModelRetry(int attempt, Exception error, Duration delay) {
                retries.incrementAndGet();
            }
        });

        AgentResult result = agent.run("session", "hello");

        assertEquals("done", result.getFinalText());
        assertEquals(2, calls.get());
        assertEquals(1, retries.get());
    }

    @Test
    void doesNotRetryWhenPolicyAllowsOnlyOneAttempt() {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                return null;
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools,
                                           Consumer<String> onDelta) {
                calls.incrementAndGet();
                throw new RuntimeException("permanent");
            }
        };
        AgentConfig config = new AgentConfig("system", model, List.of(), 1,
                ToolErrorStrategy.SEND_TO_MODEL, Duration.ofSeconds(1), 1024,
                RetryPolicy.none());
        Agent agent = new Agent(config, new InMemorySessionStore());

        assertThrows(RuntimeException.class, () -> agent.run("session", "hello"));
        assertEquals(1, calls.get());
    }
}
