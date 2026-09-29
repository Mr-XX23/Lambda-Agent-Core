package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.lambda.agent.core.RoutingModelClient.text;
import static org.junit.jupiter.api.Assertions.*;

/** Measures the agent-side optimizations: cached token counts and parallel tool calls. */
class PerformanceTest {

    // --- Token counting ---

    @Test
    void tokenLimitCountsEachMessageOnlyOnce() {
        var model = new FakeModelClient();
        var strategy = new TokenLimitStrategy(1_000_000);
        List<Message> history = new ArrayList<>(List.of(new Message(Role.SYSTEM, "sys", null)));
        int recountingCalls = 0;

        // Twenty agent steps, each adding two messages; before every model call the history is trimmed.
        for (int step = 0; step < 20; step++) {
            history.add(new Message(Role.USER, "question " + step, null));
            history.add(new Message(Role.ASSISTANT, "answer " + step, null));
            strategy.optimize(history, model);
            recountingCalls += history.size();
        }

        assertEquals(history.size(), model.tokenCountCalls.get(),
                "one count per message; without the cache this run would make " + recountingCalls + " counting calls");
    }

    // --- Parallel tool calls ---

    /** A tool that only finishes when both copies are running at the same time. */
    private static AgentTool meetingTool(String name, CountDownLatch bothRunning, AtomicInteger finished) {
        return new AgentTool() {
            public String getName() { return name; }
            public String getDescription() { return "waits for the other tool"; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(ToolInvocationContext context) throws Exception {
                bothRunning.countDown();
                boolean together = bothRunning.await(2, TimeUnit.SECONDS);
                finished.incrementAndGet();
                return ToolResult.of(together ? name + " ran in parallel" : name + " ran alone");
            }
        };
    }

    private static RoutingModelClient twoToolCalls() {
        return new RoutingModelClient(r -> {
            if (r.lastToolResult() != null) return text("done");
            ToolCall a = new ToolCall("c1", "first", "{}");
            ToolCall b = new ToolCall("c2", "second", "{}");
            return new ai.lambda.ai.core.ChatResponse(new Message(Role.ASSISTANT, "", null, null, List.of(a, b)), List.of(a, b));
        });
    }

    @Test
    void parallelToolCallsRunAtTheSameTimeAndKeepTheirOrder() {
        CountDownLatch bothRunning = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        var config = new AgentConfig("sys", twoToolCalls(),
                List.of(meetingTool("first", bothRunning, finished), meetingTool("second", bothRunning, finished)), 5)
                .withParallelToolCalls(true);

        long start = System.nanoTime();
        AgentResult result = new Agent(config, new InMemorySessionStore()).run("s", "go");
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        List<Message> messages = result.getSession().getMessages();
        assertEquals("first ran in parallel", messages.get(3).getContent());
        assertEquals("c1", messages.get(3).getToolCallId());
        assertEquals("second ran in parallel", messages.get(4).getContent());
        assertEquals("c2", messages.get(4).getToolCallId());
        assertTrue(millis < 1_500, "both tools overlapped instead of waiting for each other: " + millis + " ms");
    }

    @Test
    void toolsRunOneAtATimeByDefault() {
        CountDownLatch bothRunning = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        var config = new AgentConfig("sys", twoToolCalls(),
                List.of(meetingTool("first", bothRunning, finished), meetingTool("second", bothRunning, finished)), 5);

        AgentResult result = new Agent(config, new InMemorySessionStore()).run("s", "go");

        assertFalse(config.isParallelToolCalls());
        assertEquals("first ran alone", result.getSession().getMessages().get(3).getContent(),
                "the first tool finished before the second started");
    }

    @Test
    void aFailingParallelToolWithThrowStrategyCancelsTheOthers() {
        CountDownLatch slowStopped = new CountDownLatch(1);
        AgentTool failing = new AgentTool() {
            public String getName() { return "first"; }
            public String getDescription() { return "fails"; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(ToolInvocationContext context) { throw new IllegalStateException("boom"); }
        };
        AgentTool slow = new AgentTool() {
            public String getName() { return "second"; }
            public String getDescription() { return "slow"; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(ToolInvocationContext context) {
                try {
                    Thread.sleep(30_000);
                    return ToolResult.of("too late");
                } catch (InterruptedException e) {
                    slowStopped.countDown();
                    return ToolResult.of("stopped");
                }
            }
        };
        var config = new AgentConfig("sys", twoToolCalls(), List.of(failing, slow), 5, ToolErrorStrategy.THROW)
                .withParallelToolCalls(true);

        assertThrows(RuntimeException.class, () -> new Agent(config, new InMemorySessionStore()).run("s", "go"));

        assertDoesNotThrow(() -> assertTrue(slowStopped.await(5, TimeUnit.SECONDS), "the slow tool must be cancelled"));
    }

    @Test
    void subagentsInheritParallelToolCalls() {
        var config = new AgentConfig("sys", new FakeModelClient()).withParallelToolCalls(true)
                .withSubagents(Subagents.selfCloning());

        assertTrue(config.forSubagent("x", List.of(), new FakeModelClient(), null).isParallelToolCalls());
        assertTrue(config.withContextStrategy(new NoOpStrategy()).isParallelToolCalls());
    }
}
