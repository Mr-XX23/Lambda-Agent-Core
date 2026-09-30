package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static ai.lambda.agent.core.RoutingModelClient.callTool;
import static ai.lambda.agent.core.RoutingModelClient.text;
import static org.junit.jupiter.api.Assertions.*;

/** Session metadata shared by parallel tools, cancellation reaching tools, and the streaming switch. */
class RunControlsTest {

    private abstract static class Tool implements AgentTool {
        private final String name;

        Tool(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public String getDescription() { return name; }
        public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
    }

    // --- Session metadata

    @Test
    void metadataKeepsEveryUpdateFromManyThreads() throws Exception {
        AgentSession session = new AgentSession("s");
        int threads = 8;
        int updates = 5_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> work = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                work.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < updates; i++) {
                        session.getMetadata().merge("count", 1, (a, b) -> (Integer) a + (Integer) b);
                        session.getMetadata().put("key-" + thread + "-" + i, i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : work) future.get(30, TimeUnit.SECONDS);
        }

        assertEquals(threads * updates, session.getMetadata().get("count"));
        assertEquals(threads * updates + 1, session.getMetadata().size());
    }

    @Test
    void parallelToolsCanShareSessionMetadata() {
        CountDownLatch bothRunning = new CountDownLatch(2);
        AgentTool counter = new Tool("count") {
            public ToolResult execute(ToolInvocationContext context) throws Exception {
                bothRunning.countDown();
                bothRunning.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 2_000; i++) {
                    context.getSession().getMetadata().merge("count", 1, (a, b) -> (Integer) a + (Integer) b);
                }
                return ToolResult.of("counted");
            }
        };
        RoutingModelClient model = new RoutingModelClient(r -> {
            if (r.lastToolResult() != null) return text("done");
            ToolCall a = new ToolCall("c1", "count", "{}");
            ToolCall b = new ToolCall("c2", "count", "{}");
            return new ChatResponse(new Message(Role.ASSISTANT, "", null, null, List.of(a, b)), List.of(a, b));
        });
        AgentConfig config = new AgentConfig("sys", model, List.of(counter), 5).withParallelToolCalls(true);

        AgentResult result = new Agent(config, new InMemorySessionStore()).run("s", "go");

        assertEquals(4_000, result.getSession().getMetadata().get("count"));
    }

    @Test
    void metadataRejectsNullsInsteadOfStoringThem() {
        AgentSession session = new AgentSession("s");
        assertThrows(NullPointerException.class, () -> session.getMetadata().put("k", null));
        session.getMetadata().put("k", "v");
        session.getMetadata().remove("k");
        assertTrue(session.getMetadata().isEmpty());
    }

    // --- Cancellation

    @Test
    void aToolCanSeeThatTheRunWasCancelled() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        List<Boolean> sawCancellation = new CopyOnWriteArrayList<>();
        AgentTool longRunning = waitingTool(toolStarted, sawCancellation);
        FakeModelClient model = new FakeModelClient().replyToolCall("c1", "wait", "{}").replyText("never reached");
        Agent agent = new Agent(new AgentConfig("sys", model, List.of(longRunning), 5), new InMemorySessionStore());
        CancellationToken token = new CancellationToken();

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<AgentResult> run = pool.submit(() -> agent.run("s", "go", token));
            assertTrue(toolStarted.await(10, TimeUnit.SECONDS));
            long cancelledAt = System.nanoTime();
            token.cancel();

            Exception error = assertThrows(Exception.class, () -> run.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - cancelledAt) < 5, "the tool stopped early");
        }
        assertEquals(List.of(true), recorded(sawCancellation));
    }

    /**
     * What the tool recorded. A cancelled run returns without waiting for the tool's thread, so
     * the tool may still be finishing; give it a moment.
     */
    private static List<Boolean> recorded(List<Boolean> sawCancellation) throws InterruptedException {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sawCancellation.isEmpty() && System.nanoTime() < giveUp) Thread.sleep(5);
        return sawCancellation;
    }

    /** A tool that works until it is told to stop (for at most 10 seconds), and records whether it was told. */
    private static AgentTool waitingTool(CountDownLatch started, List<Boolean> sawCancellation) {
        return new Tool("wait") {
            public ToolResult execute(ToolInvocationContext context) throws Exception {
                started.countDown();
                long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                try {
                    while (!context.getCancellationToken().isCancelled() && System.nanoTime() < giveUp) Thread.sleep(10);
                } finally {
                    // Whether the tool noticed first or the agent interrupted it, the token says why.
                    sawCancellation.add(context.getCancellationToken().isCancelled());
                }
                return ToolResult.of("stopped");
            }
        };
    }

    @Test
    void cancellingTheRunReachesToolsInsideSubagents() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        List<Boolean> sawCancellation = new CopyOnWriteArrayList<>();
        RoutingModelClient model = new RoutingModelClient(r -> {
            if (r.isSubagent()) return r.lastToolResult() == null ? callTool("wait", "{}") : text("sub done");
            return r.lastToolResult() == null
                    ? callTool("invoke_subagent", "{\"tasks\":[{\"agent\":\"self\",\"task\":\"part A\"}]}")
                    : text("main done");
        });
        AgentConfig config = new AgentConfig("sys", model, List.of(waitingTool(toolStarted, sawCancellation)), 5)
                .withSubagents(Subagents.selfCloning());
        Agent agent = new Agent(config, new InMemorySessionStore());
        CancellationToken token = new CancellationToken();

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<AgentResult> run = pool.submit(() -> agent.run("s", "go", token));
            assertTrue(toolStarted.await(10, TimeUnit.SECONDS), "the subagent's tool did not start");
            long cancelledAt = System.nanoTime();
            token.cancel();

            Exception error = assertThrows(Exception.class, () -> run.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - cancelledAt) < 5, "the subagent stopped early");
        }
        assertEquals(List.of(true), recorded(sawCancellation));
    }

    /** A tool that never checks the token: it just works for 30 seconds, unless interrupted. */
    private static AgentTool stubbornTool(String name, boolean parallelSafe, CountDownLatch started, List<String> interrupted) {
        return new Tool(name) {
            public boolean isParallelSafe() { return parallelSafe; }

            public ToolResult execute(ToolInvocationContext context) throws Exception {
                started.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.add(context.getToolCallId());
                    throw e;
                }
                return ToolResult.of("finished");
            }
        };
    }

    private static RoutingModelClient asksFor(String... toolNames) {
        return new RoutingModelClient(r -> {
            if (r.lastToolResult() != null) return text("done");
            List<ToolCall> calls = new ArrayList<>();
            for (int i = 0; i < toolNames.length; i++) calls.add(new ToolCall("c" + (i + 1), toolNames[i], "{}"));
            return new ChatResponse(new Message(Role.ASSISTANT, "", null, null, calls), calls);
        });
    }

    /** Runs the agent, cancels once {@code started} counts down, and returns how long stopping took. */
    private static long cancelWhenStarted(Agent agent, CountDownLatch started) throws Exception {
        CancellationToken token = new CancellationToken();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<AgentResult> run = pool.submit(() -> agent.run("s", "go", token));
            assertTrue(started.await(10, TimeUnit.SECONDS), "the tool did not start");
            long cancelledAt = System.nanoTime();
            token.cancel();
            Exception error = assertThrows(Exception.class, () -> run.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt);
        }
    }

    @Test
    void cancellingARunInterruptsAToolThatDoesNotCheckTheToken() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        List<String> interrupted = new CopyOnWriteArrayList<>();
        InMemorySessionStore store = new InMemorySessionStore();
        Agent agent = new Agent(new AgentConfig("sys", asksFor("slow", "after"),
                List.of(stubbornTool("slow", false, started, interrupted), stubbornTool("after", false, new CountDownLatch(1), interrupted)), 5),
                store);
        List<String> toolStarts = new CopyOnWriteArrayList<>();
        agent.addListener(new AgentEventListener() {
            @Override
            public void onToolStart(ToolCall call, ToolInvocationContext context) {
                toolStarts.add(call.getId());
            }
        });

        long millis = cancelWhenStarted(agent, started);

        assertTrue(millis < 2_000, "stopped " + millis + " ms after cancel(), not after the 30 s tool or its timeout");
        assertEquals(List.of("c1"), interrupted, "the running tool was interrupted");
        assertEquals(List.of("c1"), toolStarts, "no tool is started once the run is cancelled");
        // Every tool call has a result, so the saved session can be continued.
        List<Message> saved = store.loadOrCreate("s").getMessages();
        List<Message> results = saved.stream().filter(m -> m.getRole() == Role.TOOL).toList();
        assertEquals(List.of("c1", "c2"), results.stream().map(Message::getToolCallId).toList());
        assertTrue(results.stream().allMatch(m -> m.getContent().contains("the run was cancelled")), results.toString());
    }

    @Test
    void cancellingARunInterruptsToolsRunningTogether() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<String> interrupted = new CopyOnWriteArrayList<>();
        Agent agent = new Agent(new AgentConfig("sys", asksFor("fetch", "fetch"),
                List.of(stubbornTool("fetch", true, bothStarted, interrupted)), 5), new InMemorySessionStore());

        long millis = cancelWhenStarted(agent, bothStarted);

        assertTrue(millis < 2_000, "stopped after " + millis + " ms");
        assertEquals(Set.of("c1", "c2"), Set.copyOf(interrupted));
    }

    @Test
    void childTokensFollowTheirParentButNotTheOtherWayRound() {
        CancellationToken parent = new CancellationToken();
        CancellationToken child = parent.child();
        CancellationToken sibling = parent.child();

        child.cancel();
        assertTrue(child.isCancelled());
        assertFalse(parent.isCancelled(), "cancelling a child leaves the parent running");
        assertFalse(sibling.isCancelled());

        parent.cancel();
        assertTrue(sibling.isCancelled());
        assertTrue(sibling.child().isCancelled());
        assertThrows(CancellationException.class, sibling::throwIfCancelled);
    }

    @Test
    void aContextCreatedWithoutATokenIsNeverCancelled() {
        ToolInvocationContext context = new ToolInvocationContext("c1", null, new AgentSession("s"));
        assertFalse(context.getCancellationToken().isCancelled());
        assertEquals("{}", context.getArgumentsJson());
        assertThrows(NullPointerException.class, () -> new ToolInvocationContext("c1", "{}", new AgentSession("s"), null));
    }

    // --- Streaming

    /** Counts which of the two model methods the agent uses. */
    private static final class CountingModel implements ModelClient {
        final AtomicInteger chatCalls = new AtomicInteger();
        final AtomicInteger streamCalls = new AtomicInteger();

        public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
            chatCalls.incrementAndGet();
            return new ChatResponse(new Message(Role.ASSISTANT, "Hello there", null), List.of());
        }

        public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
            streamCalls.incrementAndGet();
            onDelta.accept("Hello ");
            onDelta.accept("there");
            return new ChatResponse(new Message(Role.ASSISTANT, "Hello there", null), List.of());
        }
    }

    private static List<String> deltasOf(AgentConfig config, String[] finalText) {
        List<String> deltas = new ArrayList<>();
        Agent agent = new Agent(config, new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            @Override
            public void onAssistantDelta(String delta) {
                deltas.add(delta);
            }
        });
        finalText[0] = agent.run("s", "hi").getFinalText();
        return deltas;
    }

    @Test
    void streamsByDefault() {
        CountingModel model = new CountingModel();
        String[] text = new String[1];
        AgentConfig config = new AgentConfig("sys", model);

        assertTrue(config.isStreaming());
        assertEquals(List.of("Hello ", "there"), deltasOf(config, text));
        assertEquals("Hello there", text[0]);
        assertEquals(1, model.streamCalls.get());
        assertEquals(0, model.chatCalls.get());
    }

    @Test
    void withStreamingOffAsksForTheWholeReplyAtOnce() {
        CountingModel model = new CountingModel();
        String[] text = new String[1];
        AgentConfig config = new AgentConfig("sys", model).withStreaming(false);

        assertEquals(List.of(), deltasOf(config, text));
        assertEquals("Hello there", text[0]);
        assertEquals(1, model.chatCalls.get());
        assertEquals(0, model.streamCalls.get());
    }

    @Test
    void theStreamingChoiceSurvivesOtherSettingsAndReachesSubagents() {
        CountingModel model = new CountingModel();
        AgentConfig off = new AgentConfig("sys", model).withStreaming(false);

        assertFalse(off.withParallelToolCalls(true).isStreaming());
        assertFalse(off.withContextStrategy(new SlidingWindowStrategy(10)).isStreaming());
        assertFalse(off.withSubagents(Subagents.selfCloning()).isStreaming());
        assertFalse(off.forSubagent("sub", List.of(), model, null).isStreaming());
        assertTrue(off.withStreaming(true).isStreaming());
    }
}
