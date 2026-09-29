package ai.lambda.agent.core;

import ai.lambda.agent.prebuilt.EchoTool;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

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

        assertEquals("boom", e.getCause().getMessage());
    }

    @Test
    void stopsAfterMaxIterations() {
        var model = new FakeModelClient();
        for (int i = 0; i < 5; i++) model.replyToolCall("c" + i, "echo", "{\"text\":\"again\"}");

        AgentResult result = agent(model, List.of(new EchoTool()), null).run(SESSION, "loop forever");

        assertTrue(result.getFinalText().contains("max iterations"), result.getFinalText());
        assertEquals(5, model.requests.size());
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
    void largeToolResultIsShortenedInHistoryButListenersGetAllOfIt() {
        AgentTool bigTool = new AgentTool() {
            public String getName() { return "big"; }
            public String getDescription() { return "Returns a lot of text."; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(ToolInvocationContext context) { return ToolResult.of("a".repeat(1_000)); }
        };
        var model = new FakeModelClient().replyToolCall("c1", "big", "{}").replyText("done");
        var config = new AgentConfig("sys", model, List.of(bigTool), 5).withMaxToolResultChars(100);
        var agent = new Agent(config, new InMemorySessionStore());
        int[] seenByListener = {0};
        agent.addListener(new AgentEventListener() {
            @Override
            public void onToolEnd(ai.lambda.ai.core.ToolCall call, ToolResult result) {
                seenByListener[0] = result.getContent().length();
            }
        });

        AgentResult result = agent.run(SESSION, "go");

        String stored = result.getSession().getMessages().get(3).getContent();
        assertTrue(stored.contains("900 characters truncated"), stored);
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
}
