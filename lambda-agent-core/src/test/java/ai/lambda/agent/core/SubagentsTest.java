package ai.lambda.agent.core;

import ai.lambda.agent.prebuilt.EchoTool;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.lambda.agent.core.RoutingModelClient.callTool;
import static ai.lambda.agent.core.RoutingModelClient.text;
import static org.junit.jupiter.api.Assertions.*;

class SubagentsTest {

    @TempDir
    Path dir;

    private static final String SESSION = "s1";

    private static final Subagent REVIEWER = new Subagent("reviewer",
            "Reviews code for bugs.", "You are a strict code reviewer.", List.of("echo"));

    /** A tool that only exists so we can check subagents don't get it. */
    private static final AgentTool SECRET = new AgentTool() {
        public String getName() { return "secret"; }
        public String getDescription() { return "Not for subagents."; }
        public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        public ToolResult execute(ToolInvocationContext context) { return ToolResult.of("classified"); }
    };

    private static String invoke(String... agentTaskPairs) {
        StringBuilder sb = new StringBuilder("{\"tasks\":[");
        for (int i = 0; i < agentTaskPairs.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"agent\":\"").append(agentTaskPairs[i]).append("\",\"task\":\"")
                    .append(agentTaskPairs[i + 1]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static AgentResult run(AgentConfig config, String input) {
        return new Agent(config, new InMemorySessionStore()).run(SESSION, input);
    }

    // --- Loading definition files ---

    @Test
    void loadsDefinitionFilesWithToolListsAndModels() throws IOException {
        Files.writeString(dir.resolve("reviewer.md"), """
                ---
                name: reviewer
                description: Reviews code.
                tools: [read_file, "echo"]
                model: pro
                ---
                You review code.
                """);
        Files.createDirectories(dir.resolve("tester"));
        Files.writeString(dir.resolve("tester/agent.md"), """
                ---
                name: tester
                description: >
                  Writes tests
                  for classes.
                tools:
                  - read_file
                  - write_file
                ---
                You write tests.
                """);
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        ModelClient pro = new FakeModelClient();

        Subagents subagents = Subagents.load(dir, Map.of("pro", pro));

        assertEquals(List.of("reviewer", "tester"), subagents.all().stream().map(Subagent::name).toList());
        Subagent reviewer = subagents.find("reviewer").orElseThrow();
        assertEquals(List.of("read_file", "echo"), reviewer.tools());
        assertSame(pro, reviewer.model());
        assertEquals("You review code.", reviewer.instructions());
        Subagent tester = subagents.find("tester").orElseThrow();
        assertEquals("Writes tests for classes.", tester.description());
        assertEquals(List.of("read_file", "write_file"), tester.tools());
        assertNull(tester.model(), "no model means the main agent's model");
    }

    @Test
    void definitionErrorsAreClear() throws IOException {
        Files.writeString(dir.resolve("a.md"), "---\nname: a\ndescription: A.\nmodel: ultra\n---\nbody");
        IllegalArgumentException unknownModel = assertThrows(IllegalArgumentException.class, () -> Subagents.load(dir));
        assertTrue(unknownModel.getMessage().contains("a.md") && unknownModel.getMessage().contains("unknown model 'ultra'"),
                unknownModel.getMessage());

        Files.writeString(dir.resolve("a.md"), "---\nname: self\ndescription: A.\n---\nbody");
        assertThrows(IllegalArgumentException.class, () -> Subagents.load(dir));

        Files.writeString(dir.resolve("a.md"), "---\nname: dup\ndescription: A.\n---\nbody");
        Files.writeString(dir.resolve("b.md"), "---\nname: dup\ndescription: B.\n---\nbody");
        assertThrows(IllegalArgumentException.class, () -> Subagents.load(dir));
    }

    @Test
    void emptySubagentsAreRejected() {
        AgentConfig config = new AgentConfig("sys", new FakeModelClient());
        assertThrows(IllegalArgumentException.class, () -> config.withSubagents(Subagents.of()));
    }

    @Test
    void agentRejectsSubagentToolsItDoesNotHave() {
        AgentConfig config = new AgentConfig("sys", new FakeModelClient(), List.of(), 5)
                .withSubagents(Subagents.of(REVIEWER));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Agent(config, new InMemorySessionStore()));
        assertTrue(e.getMessage().contains("'reviewer' uses tool 'echo'"), e.getMessage());
    }

    // --- Delegating ---

    @Test
    void staticSubagentStartsCleanWithOnlyItsToolsInstructionsAndModel() {
        RoutingModelClient reviewerModel = new RoutingModelClient(r -> text("reviewed: " + r.lastUser()));
        RoutingModelClient mainModel = new RoutingModelClient(r -> r.lastToolResult() == null
                ? callTool("invoke_subagent", invoke("reviewer", "Review Foo.java"))
                : text("main got: " + r.lastToolResult()));
        AgentConfig config = new AgentConfig("You are the lead.", mainModel, List.of(new EchoTool(), SECRET), 5)
                .withSubagents(Subagents.of(REVIEWER.withModel(reviewerModel)));

        AgentResult result = run(config, "check the code, and remember my name is Ada");

        assertTrue(result.getFinalText().contains("reviewed: Review Foo.java"), result.getFinalText());

        RoutingModelClient.Request main = mainModel.requests.get(0);
        assertTrue(main.toolNames().contains("invoke_subagent"));
        assertTrue(main.system().contains("## Subagents") && main.system().contains("- reviewer: Reviews code for bugs."),
                main.system());

        assertEquals(1, reviewerModel.requests.size());
        RoutingModelClient.Request child = reviewerModel.requests.get(0);
        assertEquals(List.of(Role.SYSTEM, Role.USER), child.messages().stream().map(Message::getRole).toList(),
                "a subagent starts with a clean slate");
        assertFalse(child.lastUser().contains("Ada"), "the main conversation is not shared");
        assertTrue(child.system().startsWith("You are a strict code reviewer."), child.system());
        assertEquals(List.of("echo"), child.toolNames(), "only its own tools; specialists cannot delegate");
    }

    @Test
    void tasksInOneCallRunInParallelAndKeepTheirOrder() {
        CountDownLatch bothRunning = new CountDownLatch(2);
        RoutingModelClient model = new RoutingModelClient(r -> {
            if (r.isSubagent()) {
                bothRunning.countDown();
                try {
                    // Only returns in time if the other subagent is running at the same time.
                    boolean parallel = bothRunning.await(5, TimeUnit.SECONDS);
                    return text(parallel ? "did " + r.lastUser() : "NOT PARALLEL");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            return r.lastToolResult() == null
                    ? callTool("invoke_subagent", invoke("self", "part A", "self", "part B"))
                    : text(r.lastToolResult());
        });
        AgentConfig config = new AgentConfig("sys", model, List.of(), 5)
                .withSubagents(Subagents.selfCloning().withMaxParallel(2));

        String out = run(config, "big job").getFinalText();

        assertTrue(out.startsWith("2 subagent tasks finished."), out);
        assertFalse(out.contains("NOT PARALLEL"), out);
        int first = out.indexOf("### Task 1: self\ndid part A");
        int second = out.indexOf("### Task 2: self\ndid part B");
        assertTrue(first >= 0 && second > first, out);
    }

    @Test
    void oneFailedTaskDoesNotStopTheOthers() {
        List<String> events = new CopyOnWriteArrayList<>();
        RoutingModelClient model = new RoutingModelClient(r -> {
            if (r.isSubagent()) {
                if (r.lastUser().equals("bad")) throw new IllegalStateException("model exploded");
                return text("fine");
            }
            return r.lastToolResult() == null
                    ? callTool("invoke_subagent", invoke("self", "bad", "self", "good"))
                    : text(r.lastToolResult());
        });
        Agent agent = new Agent(new AgentConfig("sys", model, List.of(), 5)
                .withSubagents(Subagents.selfCloning()), new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            public void onSubagentStart(String subagent, String task, int depth) { events.add("start " + task); }
            public void onSubagentEnd(String subagent, int depth, AgentResult result) { events.add("end"); }
            public void onSubagentError(String subagent, int depth, Exception error) { events.add("error"); }
        });

        String out = agent.run(SESSION, "go").getFinalText();

        assertTrue(out.contains("(1 failed)"), out);
        assertTrue(out.contains("### Task 1: self\nFAILED: model exploded"), out);
        assertTrue(out.contains("### Task 2: self\nfine"), out);
        assertEquals(Set.of("start bad", "start good", "end", "error"), Set.copyOf(events));
    }

    @Test
    void selfClonesDelegateOnlyUpToMaxDepth() {
        List<Integer> depths = new CopyOnWriteArrayList<>();
        RoutingModelClient model = new RoutingModelClient(r -> {
            boolean canDelegate = r.toolNames().contains("invoke_subagent");
            if (r.lastToolResult() == null && canDelegate) {
                return callTool("invoke_subagent", invoke("self", r.isSubagent() ? "inner" : "outer"));
            }
            return text(r.isSubagent() ? "answer to " + r.lastUser() : "main: " + r.lastToolResult());
        });
        Agent agent = new Agent(new AgentConfig("sys", model, List.of(), 5)
                .withSubagents(Subagents.selfCloning().withMaxDepth(2)), new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            public void onSubagentStart(String subagent, String task, int depth) { depths.add(depth); }
        });

        String out = agent.run(SESSION, "go").getFinalText();

        assertEquals(List.of(1, 2), depths);
        assertTrue(out.contains("answer to outer"), out);
        RoutingModelClient.Request deepest = model.subagentRequests().stream()
                .filter(r -> r.lastUser().equals("inner")).findFirst().orElseThrow();
        assertFalse(deepest.toolNames().contains("invoke_subagent"), "depth 2 is the limit");
        assertFalse(deepest.system().contains("## Subagents"), "no delegation instructions without the tool");
    }

    @Test
    void invalidCallsAreSentBackToTheModel() {
        RoutingModelClient model = new RoutingModelClient(r -> r.lastToolResult() == null
                ? callTool("invoke_subagent", r.lastUser().equals("ghost")
                        ? invoke("ghost", "x")
                        : invoke("self", "a", "self", "b"))
                : text(r.lastToolResult()));
        AgentConfig config = new AgentConfig("sys", model, List.of(new EchoTool()), 5)
                .withSubagents(Subagents.of(REVIEWER).withSelfCloning(true).withMaxTasksPerCall(1));

        String unknown = run(config, "ghost").getFinalText();
        String tooMany = run(config, "many").getFinalText();

        assertTrue(unknown.contains("unknown subagent 'ghost'. Available subagents: self, reviewer"), unknown);
        assertTrue(tooMany.contains("at most 1 tasks per call"), tooMany);
        assertTrue(model.subagentRequests().isEmpty(), "no subagent ran");
    }

    @Test
    void subagentsInheritThePermissionPolicy() {
        AtomicInteger writes = new AtomicInteger();
        AgentTool write = new AgentTool() {
            public String getName() { return "write_note"; }
            public String getDescription() { return "Writes a note."; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolPolicy getPolicy() {
                return new ToolPolicy(false, Duration.ofSeconds(5), 1000, Set.of(ToolCapability.WRITE));
            }
            public ToolResult execute(ToolInvocationContext context) { writes.incrementAndGet(); return ToolResult.of("ok"); }
        };
        List<String> childSessions = new CopyOnWriteArrayList<>();
        RoutingModelClient model = new RoutingModelClient(r -> {
            if (r.isSubagent()) {
                return r.lastToolResult() == null ? callTool("write_note", "{}") : text("child saw: " + r.lastToolResult());
            }
            return r.lastToolResult() == null
                    ? callTool("invoke_subagent", invoke("self", "write a note"))
                    : text(r.lastToolResult());
        });
        ToolPermissionPolicy noWrites = (session, tool, capabilities) -> !capabilities.contains(ToolCapability.WRITE);
        AgentConfig config = new AgentConfig("sys", model, List.of(write), 5, ToolErrorStrategy.SEND_TO_MODEL,
                Duration.ofMinutes(1), 1024, RetryPolicy.none(), (s, c) -> false, noWrites)
                .withSubagents(Subagents.selfCloning());
        Agent agent = new Agent(config, new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            public void onSubagentEnd(String subagent, int depth, AgentResult result) {
                childSessions.add(result.getSession().getId());
            }
        });

        String out = agent.run(SESSION, "go").getFinalText();

        assertEquals(0, writes.get());
        assertTrue(out.contains("child saw: Tool 'write_note' was denied"), out);
        assertTrue(childSessions.get(0).startsWith(SESSION + ":self-1-"),
                "subagent session ids start with the parent's, so session-based policies still match");
    }

    @Test
    void subagentsAreStoppedWhenTheMainAgentRunsOutOfTime() throws InterruptedException {
        CountDownLatch childStopped = new CountDownLatch(1);
        RoutingModelClient model = new RoutingModelClient(r -> {
            if (r.isSubagent()) {
                try {
                    Thread.sleep(30_000); // a subagent that would run far too long
                    return text("too late");
                } catch (InterruptedException e) {
                    childStopped.countDown();
                    throw new RuntimeException("interrupted", e);
                }
            }
            return r.lastToolResult() == null ? callTool("invoke_subagent", invoke("self", "slow")) : text("done");
        });
        AgentConfig config = new AgentConfig("sys", model, List.of(), 5, ToolErrorStrategy.SEND_TO_MODEL,
                Duration.ofMillis(300), 1024).withSubagents(Subagents.selfCloning());

        assertThrows(RuntimeException.class, () -> run(config, "go"));

        assertTrue(childStopped.await(5, TimeUnit.SECONDS), "the running subagent must be interrupted");
    }

    @Test
    void subagentTranscriptIsAvailableToListeners() {
        List<AgentResult> results = new CopyOnWriteArrayList<>();
        RoutingModelClient model = new RoutingModelClient(r -> r.isSubagent() ? text("sub answer")
                : r.lastToolResult() == null ? callTool("invoke_subagent", invoke("self", "do it"))
                : text("done"));
        Agent agent = new Agent(new AgentConfig("sys", model, List.of(), 5)
                .withSubagents(Subagents.selfCloning()), new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            public void onSubagentEnd(String subagent, int depth, AgentResult result) { results.add(result); }
        });

        agent.run(SESSION, "go");

        List<Message> transcript = results.get(0).getSession().getMessages();
        assertEquals(List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT),
                transcript.stream().map(Message::getRole).toList());
        assertEquals("do it", transcript.get(1).getContent());
        assertEquals("sub answer", transcript.get(2).getContent());
    }
}
