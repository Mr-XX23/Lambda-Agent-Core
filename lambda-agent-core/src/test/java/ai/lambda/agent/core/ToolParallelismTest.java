package ai.lambda.agent.core;

import ai.lambda.agent.prebuilt.EchoTool;
import ai.lambda.agent.prebuilt.FileReadTool;
import ai.lambda.agent.prebuilt.FileWriteTool;
import ai.lambda.agent.prebuilt.NetworkFetchTool;
import ai.lambda.agent.prebuilt.ProcessTool;
import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.lambda.agent.core.RoutingModelClient.text;
import static org.junit.jupiter.api.Assertions.*;

class ToolParallelismTest {

    /** What the tools did, in the order it happened: "start a", "end a", ... */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger mostRunningAtOnce = new AtomicInteger();

    /** A tool that takes a moment, records when it starts and ends, and counts how many run at once. */
    private AgentTool tool(String name, boolean parallelSafe) {
        return new AgentTool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public boolean isParallelSafe() { return parallelSafe; }

            public ToolResult execute(ToolInvocationContext context) throws Exception {
                events.add("start " + context.getToolCallId());
                mostRunningAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.sleep(150);
                running.decrementAndGet();
                events.add("end " + context.getToolCallId());
                return ToolResult.of(context.getToolCallId() + " done");
            }
        };
    }

    /** A model that asks for these tools in one reply (call ids are name#position), then answers. */
    private static RoutingModelClient asksFor(String... toolNames) {
        return new RoutingModelClient(r -> {
            if (r.lastToolResult() != null) return text("done");
            List<ToolCall> calls = new ArrayList<>();
            for (int i = 0; i < toolNames.length; i++) calls.add(new ToolCall(toolNames[i] + "#" + (i + 1), toolNames[i], "{}"));
            return new ChatResponse(new Message(Role.ASSISTANT, "", null, null, calls), calls);
        });
    }

    private AgentResult run(AgentConfig config) {
        return new Agent(config, new InMemorySessionStore()).run("s", "go");
    }

    private boolean before(String first, String second) {
        assertTrue(events.contains(first) && events.contains(second), events.toString());
        return events.indexOf(first) < events.indexOf(second);
    }

    private static List<String> toolResults(AgentResult result) {
        return result.getSession().getMessages().stream()
                .filter(m -> m.getRole() == Role.TOOL).map(Message::getContent).toList();
    }

    @Test
    void byDefaultSafeToolsRunTogether() {
        AgentConfig config = new AgentConfig("sys", asksFor("search", "fetch", "fetch"),
                List.of(tool("search", true), tool("fetch", true)), 5);

        AgentResult result = run(config);

        assertEquals(ToolParallelism.AUTO, config.getToolParallelism());
        assertEquals(3, mostRunningAtOnce.get(), events.toString());
        assertEquals(List.of("search#1 done", "fetch#2 done", "fetch#3 done"), toolResults(result), "results keep the model's order");
    }

    @Test
    void byDefaultOtherToolsRunOneAtATime() {
        run(new AgentConfig("sys", asksFor("write", "write", "update"),
                List.of(tool("write", false), tool("update", false)), 5));

        assertEquals(1, mostRunningAtOnce.get());
        assertEquals(List.of("start write#1", "end write#1", "start write#2", "end write#2", "start update#3", "end update#3"), events);
    }

    @Test
    void aToolThatIsNotSafeWaitsForEarlierOnesAndRunsAlone() {
        AgentResult result = run(new AgentConfig("sys", asksFor("search", "fetch", "write", "fetch", "fetch"),
                List.of(tool("search", true), tool("fetch", true), tool("write", false)), 5));

        // The two reads before the write overlap.
        assertTrue(before("start search#1", "end fetch#2") && before("start fetch#2", "end search#1"), events.toString());
        // The write starts only after both have ended, and nothing starts until it has ended.
        assertTrue(before("end search#1", "start write#3") && before("end fetch#2", "start write#3"), events.toString());
        assertTrue(before("end write#3", "start fetch#4") && before("end write#3", "start fetch#5"), events.toString());
        // The two reads after the write overlap again.
        assertTrue(before("start fetch#4", "end fetch#5") && before("start fetch#5", "end fetch#4"), events.toString());
        assertEquals(2, mostRunningAtOnce.get());
        assertEquals(List.of("search#1 done", "fetch#2 done", "write#3 done", "fetch#4 done", "fetch#5 done"), toolResults(result));
    }

    @Test
    void noMoreThanTheLimitRunAtOnce() {
        AgentConfig config = new AgentConfig("sys", asksFor("fetch", "fetch", "fetch", "fetch", "fetch", "fetch", "fetch"),
                List.of(tool("fetch", true)), 5).withMaxParallelTools(3);

        AgentResult result = run(config);

        assertEquals(3, mostRunningAtOnce.get(), events.toString());
        assertEquals(7, toolResults(result).size());
        assertEquals("fetch#1 done", toolResults(result).getFirst());
        assertEquals("fetch#7 done", toolResults(result).getLast());
    }

    @Test
    void neverRunsEvenSafeToolsOneAtATime() {
        run(new AgentConfig("sys", asksFor("fetch", "fetch", "fetch"), List.of(tool("fetch", true)), 5)
                .withToolParallelism(ToolParallelism.NEVER));

        assertEquals(1, mostRunningAtOnce.get());
    }

    @Test
    void alwaysRunsEveryToolTogether() {
        run(new AgentConfig("sys", asksFor("write", "fetch", "write"),
                List.of(tool("write", false), tool("fetch", true)), 5).withToolParallelism(ToolParallelism.ALWAYS));

        assertEquals(3, mostRunningAtOnce.get(), events.toString());
    }

    @Test
    void aToolThatIsDeniedDoesNotHoldUpTheOthers() {
        ToolPermissionPolicy noWrites = (sessionId, tool, capabilities) -> !tool.getName().equals("write");
        AgentConfig base = new AgentConfig("sys", asksFor("fetch", "write", "fetch"),
                List.of(tool("fetch", true), tool("write", false)), 5);
        AgentConfig config = new AgentConfig(base.getSystemPrompt(), base.getModelClient(), base.getTools(), 5,
                ToolErrorStrategy.SEND_TO_MODEL, base.getRunTimeout(), base.getMaxToolArgumentLength(),
                RetryPolicy.none(), (sessionId, call) -> true, noWrites);

        AgentResult result = run(config);

        assertEquals(2, mostRunningAtOnce.get(), "the denied write never ran, so the two reads overlap");
        assertEquals("fetch#1 done", toolResults(result).get(0));
        assertTrue(toolResults(result).get(1).contains("denied"), toolResults(result).get(1));
        assertEquals("fetch#3 done", toolResults(result).get(2));
    }

    @Test
    void settingsAreKeptAcrossCopiesAndValidated() {
        AgentConfig config = new AgentConfig("sys", new FakeModelClient());
        assertEquals(ToolParallelism.AUTO, config.getToolParallelism());
        assertEquals(AgentConfig.DEFAULT_MAX_PARALLEL_TOOLS, config.getMaxParallelTools());
        assertFalse(config.isParallelToolCalls());

        assertEquals(ToolParallelism.ALWAYS, config.withParallelToolCalls(true).getToolParallelism());
        assertTrue(config.withParallelToolCalls(true).isParallelToolCalls());
        assertEquals(ToolParallelism.NEVER, config.withParallelToolCalls(false).getToolParallelism());

        AgentConfig changed = config.withToolParallelism(ToolParallelism.NEVER).withMaxParallelTools(2);
        AgentConfig copy = changed.withStreaming(false).withContextStrategy(new NoOpStrategy())
                .withSubagents(Subagents.selfCloning());
        assertEquals(ToolParallelism.NEVER, copy.getToolParallelism());
        assertEquals(2, copy.getMaxParallelTools());
        assertEquals(2, copy.forSubagent("sub", List.of(), new FakeModelClient(), null).getMaxParallelTools());

        assertThrows(IllegalArgumentException.class, () -> config.withMaxParallelTools(0));
        assertThrows(NullPointerException.class, () -> config.withToolParallelism(null));
    }

    @Test
    void builtInToolsThatOnlyReadAreSafeAndOnesThatChangeThingsAreNot() {
        assertTrue(new FileReadTool(Path.of(".")).isParallelSafe());
        assertTrue(new NetworkFetchTool(Set.of("example.com")).isParallelSafe());
        assertTrue(new EchoTool().isParallelSafe());
        assertFalse(new FileWriteTool(Path.of(".")).isParallelSafe());
        assertFalse(new ProcessTool(Set.of("hostname")).isParallelSafe());

        McpTool call = (arguments, context) -> ToolResult.of("ok");
        assertFalse(new McpToolAdapter("t", "d", "{}", call).isParallelSafe(), "not safe unless declared");
        assertFalse(new McpToolAdapter("t", "d", "{}", ToolPolicy.unrestricted(), call).isParallelSafe());
        assertTrue(new McpToolAdapter("t", "d", "{}", ToolPolicy.unrestricted(), call, true).isParallelSafe());
    }
}
