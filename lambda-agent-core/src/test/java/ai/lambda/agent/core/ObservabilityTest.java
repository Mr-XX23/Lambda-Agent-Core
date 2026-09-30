package ai.lambda.agent.core;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ObservabilityTest {

    private static AgentTool tool(String name, boolean fails) {
        return new AgentTool() {
            public String getName() { return name; }
            public String getDescription() { return name; }
            public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ToolResult execute(ToolInvocationContext context) {
                if (fails) throw new IllegalStateException("boom");
                return ToolResult.of("ok");
            }
        };
    }

    /** An agent whose model calls a working tool, then a failing tool, then answers. */
    private static Agent agentUsingTools() {
        FakeModelClient model = new FakeModelClient()
                .replyToolCall("c1", "lookup", "{}")
                .replyToolCall("c2", "broken", "{}")
                .replyText("done");
        return new Agent(new AgentConfig("system", model, List.of(tool("lookup", false), tool("broken", true)), 5),
                new InMemorySessionStore());
    }

    private static long count(InMemoryMetricRecorder metrics, String name) {
        return metrics.snapshot().stream().filter(m -> m.name().equals(name)).mapToLong(InMemoryMetricRecorder.Metric::value).sum();
    }

    @Test
    void countsRunsIterationsModelAndToolCalls() {
        InMemoryMetricRecorder metrics = new InMemoryMetricRecorder();
        InMemoryAgentTracer tracer = new InMemoryAgentTracer();
        ObservabilityAgentTracer observed = new ObservabilityAgentTracer(tracer, metrics);
        Agent agent = agentUsingTools();
        agent.addListener(observed);

        agent.run("s", "go");

        assertEquals(1, count(metrics, "agent.runs"));
        assertEquals(3, count(metrics, "agent.iterations"));
        assertEquals(3, count(metrics, "model.calls"));
        assertEquals(2, count(metrics, "tool.calls"));
        assertEquals(1, count(metrics, "tool.errors"));
        assertTrue(metrics.snapshot().stream().anyMatch(m -> m.name().equals("tool.errors") && "broken".equals(m.tags().get("tool"))));
        assertTrue(metrics.snapshot().stream().noneMatch(InMemoryMetricRecorder.Metric::timer));
        assertEquals(tracer.snapshot(), observed.snapshot(), "events still reach the wrapped tracer");
        assertTrue(tracer.snapshot().stream().anyMatch(e -> e.type() == TraceEventType.TOOL_FAILED));

        metrics.timer("model.latency", 12, Map.of("model", "fake"));
        assertTrue(metrics.snapshot().getLast().timer());
    }

    @Test
    void exportsEveryEventOfAnAgentRun() {
        List<TraceEvent> exported = new ArrayList<>();
        InMemoryAgentTracer tracer = new InMemoryAgentTracer();
        ExportingAgentTracer exporting = new ExportingAgentTracer(tracer, exported::add);
        Agent agent = agentUsingTools();
        agent.addListener(exporting);

        agent.run("s", "go");

        assertFalse(exported.isEmpty(), "an agent run must produce exported events");
        assertEquals(tracer.snapshot(), exported, "each recorded event is exported once, in order");
        assertEquals(TraceEventType.RUN_STARTED, exported.getFirst().type());
        assertEquals(TraceEventType.RUN_COMPLETED, exported.getLast().type());
        assertEquals(2, exported.stream().filter(e -> e.type() == TraceEventType.TOOL_STARTED).count());
    }

    @Test
    void exportsAcceptedEventsAndSkipsOnesRecordedEarlier() {
        InMemoryAgentTracer tracer = new InMemoryAgentTracer();
        TraceEvent earlier = new TraceEvent(Instant.now(), "r0", "s", "t", TraceEventType.RUN_STARTED, "agent.run", 0, Map.of());
        tracer.accept(earlier);
        List<TraceEvent> exported = new ArrayList<>();
        ExportingAgentTracer exporting = new ExportingAgentTracer(tracer, exported::add);

        TraceEvent event = new TraceEvent(Instant.now(), "r1", "s", "t", TraceEventType.TOOL_STARTED, "lookup", 0, Map.of("k", "v"));
        exporting.accept(event);

        assertEquals(List.of(event), exported);
        assertEquals(List.of(earlier, event), exporting.snapshot());

        // A tracer that keeps nothing still has its accepted events exported.
        List<TraceEvent> direct = new ArrayList<>();
        new ExportingAgentTracer(List::of, direct::add).accept(event);
        assertEquals(List.of(event), direct);
    }

    @Test
    void replaysRecordedEventsIntoAnotherTracer() {
        InMemoryAgentTracer recorded = new InMemoryAgentTracer();
        Agent agent = agentUsingTools();
        agent.addListener(recorded);
        agent.run("s", "go");

        List<TraceEvent> replayed = new TraceReplay().replay(recorded.snapshot(), new InMemoryAgentTracer());

        assertEquals(recorded.snapshot(), replayed);
        assertThrows(NullPointerException.class, () -> new TraceReplay().replay(null, new InMemoryAgentTracer()));
    }

    @Test
    void otlpExporterPostsEventsAsJson() throws Exception {
        BlockingQueue<String[]> received = new LinkedBlockingQueue<>();
        HttpServer collector = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            received.add(new String[]{
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        collector.start();
        try (OtlpTraceExporter exporter = new OtlpTraceExporter(
                URI.create("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces"), "Bearer abc")) {
            exporter.export(new TraceEvent(Instant.now(), "run-1", "session-1", "trace-1",
                    TraceEventType.TOOL_COMPLETED, "lookup", 42, Map.of("resultLength", 7)));

            String[] request = received.poll(10, TimeUnit.SECONDS);
            assertNotNull(request, "the collector received nothing");
            assertEquals("POST", request[0]);
            assertEquals("Bearer abc", request[1]);
            assertEquals("application/json", request[2]);
            JSONObject body = new JSONObject(request[3]);
            assertEquals("trace-1", body.getString("traceId"));
            assertEquals("run-1", body.getString("runId"));
            assertEquals("TOOL_COMPLETED", body.getString("type"));
            assertEquals(42, body.getLong("durationMillis"));
            assertEquals(7, body.getJSONObject("attributes").getInt("resultLength"));
        } finally {
            collector.stop(0);
        }
    }

    @Test
    void otlpExporterIgnoresAnUnreachableCollector() {
        try (OtlpTraceExporter exporter = new OtlpTraceExporter(URI.create("http://127.0.0.1:1/v1/traces"))) {
            assertDoesNotThrow(() -> exporter.export(new TraceEvent(Instant.now(), "r", "s", "t",
                    TraceEventType.RUN_STARTED, "agent.run", 0, Map.of())));
        }
    }

    @Test
    void traceContextScopesNestAndRestore() {
        TraceContext.clear();
        assertEquals("", TraceContext.currentTraceId());
        try (TraceContext.Scope outer = TraceContext.open("outer")) {
            assertEquals("outer", TraceContext.currentTraceId());
            try (TraceContext.Scope inner = TraceContext.open("inner")) {
                assertEquals("inner", TraceContext.currentTraceId());
            }
            assertEquals("outer", TraceContext.currentTraceId());
            // The seven-argument constructor picks up the current trace id.
            assertEquals("outer", new TraceEvent(null, "r", "s", TraceEventType.RUN_STARTED, "n", 0, null).traceId());
        }
        assertEquals("", TraceContext.currentTraceId());

        try (TraceContext.Scope generated = TraceContext.open(" ")) {
            assertFalse(TraceContext.currentTraceId().isBlank(), "a blank id is replaced by a generated one");
        }
        TraceContext.activate("manual");
        assertEquals("manual", TraceContext.currentTraceId());
        TraceContext.clear();
    }

    @Test
    void traceEventsDefaultAndValidateTheirFields() {
        TraceEvent event = new TraceEvent(null, "r", "s", "t", TraceEventType.RUN_STARTED, "n", 0, null);
        assertNotNull(event.timestamp());
        assertEquals(Map.of(), event.attributes());
        assertThrows(IllegalArgumentException.class,
                () -> new TraceEvent(null, "r", "s", "t", TraceEventType.RUN_STARTED, "n", -1, null));
        assertEquals(Map.of(), new TraceSpan("t", "s", null, "n", Instant.now(), 1, null).attributes());
    }
}
