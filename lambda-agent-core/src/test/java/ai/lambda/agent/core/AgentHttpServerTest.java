package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ModelClient;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolSchema;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentHttpServerTest {

    private static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private final HttpClient http = HttpClient.newHttpClient();
    private AgentHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private static Agent agentReplying(String... replies) {
        FakeModelClient model = new FakeModelClient();
        for (String reply : replies) model.replyText(reply);
        return new Agent(new AgentConfig("system", model), new InMemorySessionStore());
    }

    private void start(Agent agent, HttpServerConfig config, Map<String, Workflow> workflows) throws IOException {
        server = new AgentHttpServer(agent, LOOPBACK, config, workflows);
        server.start();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    }

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder builder = request(path).POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String runBody(String input) {
        return new JSONObject().put("sessionId", "s1").put("input", input).toString();
    }

    @Test
    void runsTheAgentAndReturnsJson() throws Exception {
        start(agentReplying("Hello!"), HttpServerConfig.defaults(), Map.of());

        HttpResponse<String> response = post("/agent/run", runBody("hi"));

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        JSONObject json = new JSONObject(response.body());
        assertEquals("Hello!", json.getString("text"));
        assertEquals(1, json.getInt("iterations"));
        assertFalse(json.getString("runId").isBlank());
    }

    @Test
    void returnsAServerSentEventWhenAsked() throws Exception {
        start(agentReplying("Hello!"), HttpServerConfig.defaults(), Map.of());

        HttpResponse<String> response = post("/agent/run", runBody("hi"), "Accept", "text/event-stream");

        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.body().startsWith("event: result\ndata: {"), response.body());
        assertTrue(response.body().endsWith("\n\n"));
    }

    @Test
    void requiresTheBearerTokenWhenConfigured() throws Exception {
        start(agentReplying("Hello!"), new HttpServerConfig("s3cret", 1024, 4096), Map.of());

        assertEquals(401, post("/agent/run", runBody("hi")).statusCode());
        assertEquals(401, post("/agent/run", runBody("hi"), "Authorization", "Bearer wrong").statusCode());
        assertEquals(401, post("/agent/run", runBody("hi"), "Authorization", "s3cret").statusCode());
        assertEquals(401, post("/workflow/run", "{}").statusCode());
        assertEquals(200, post("/agent/run", runBody("hi"), "Authorization", "Bearer s3cret").statusCode());
    }

    @Test
    void refusesToListenBeyondLoopbackWithoutAToken() {
        Agent agent = agentReplying();
        assertThrows(IllegalArgumentException.class,
                () -> new AgentHttpServer(agent, new InetSocketAddress("0.0.0.0", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentHttpServer(agent, new InetSocketAddress("0.0.0.0", 0), new HttpServerConfig(" ", 1024, 1024)));
    }

    @Test
    void rejectsWrongMethodBadJsonAndMissingFields() throws Exception {
        start(agentReplying(), HttpServerConfig.defaults(), Map.of());

        assertEquals(405, http.send(request("/agent/run").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, post("/agent/run", "not json").statusCode());
        assertEquals(400, post("/agent/run", "{\"sessionId\":\"s1\"}").statusCode());
    }

    @Test
    void limitsRequestAndResponseSize() throws Exception {
        start(agentReplying("x".repeat(500)), new HttpServerConfig(null, 200, 300), Map.of());

        HttpResponse<String> tooLarge = post("/agent/run", runBody("y".repeat(400)));
        assertEquals(413, tooLarge.statusCode());

        HttpResponse<String> bigAnswer = post("/agent/run", runBody("hi"));
        assertEquals(500, bigAnswer.statusCode());
        assertFalse(bigAnswer.body().contains("xxxx"), "an oversized answer is not sent");
    }

    @Test
    void agentFailuresDoNotLeakDetails() throws Exception {
        ModelClient failing = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                throw new IllegalStateException("api key sk-secret-123 rejected");
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
                return chat(messages, tools);
            }
        };
        start(new Agent(new AgentConfig("system", failing), new InMemorySessionStore()), HttpServerConfig.defaults(), Map.of());

        HttpResponse<String> response = post("/agent/run", runBody("hi"));

        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("sk-secret"), response.body());
    }

    @Test
    void refusesRequestsBeyondTheConcurrencyLimit() throws Exception {
        CountDownLatch modelCalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelClient slow = new ModelClient() {
            public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
                modelCalled.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new ChatResponse(new Message(Role.ASSISTANT, "done", null), List.of());
            }

            public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
                return chat(messages, tools);
            }
        };
        start(new Agent(new AgentConfig("system", slow), new InMemorySessionStore()),
                new HttpServerConfig(null, 1024, 4096, 1), Map.of());

        CompletableFuture<HttpResponse<String>> first = http.sendAsync(
                request("/agent/run").POST(HttpRequest.BodyPublishers.ofString(runBody("hi"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(modelCalled.await(10, TimeUnit.SECONDS));
        try {
            assertEquals(429, post("/agent/run", runBody("second")).statusCode());
        } finally {
            release.countDown();
        }
        assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode());
        assertEquals(400, post("/agent/run", "not json").statusCode(), "the permit was returned");
    }

    @Test
    void runsNamedWorkflows() throws Exception {
        Workflow workflow = new Workflow("greet", List.<WorkflowStep>of(
                (context, token) -> context.put("greeting", "hello " + context.get("name"))),
                new InMemoryCheckpointStore());
        start(agentReplying(), HttpServerConfig.defaults(), Map.of("greet", workflow));

        HttpResponse<String> ok = post("/workflow/run", new JSONObject().put("workflow", "greet")
                .put("executionId", "run-1").put("state", new JSONObject().put("name", "Ada")).toString());
        assertEquals(200, ok.statusCode());
        JSONObject json = new JSONObject(ok.body());
        assertEquals("COMPLETED", json.getString("status"));
        assertEquals("run-1", json.getString("executionId"));
        assertEquals(1, json.getInt("nextStep"));

        assertEquals(404, post("/workflow/run", "{\"workflow\":\"nope\",\"executionId\":\"x\"}").statusCode());
        assertEquals(400, post("/workflow/run", "{\"workflow\":\"greet\"}").statusCode());
        assertEquals(405, http.send(request("/workflow/run").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void configRejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new HttpServerConfig(null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new HttpServerConfig(null, 10, 10, 0));
        assertFalse(HttpServerConfig.defaults().requiresAuthentication());
        assertTrue(new HttpServerConfig("t", 10, 10).requiresAuthentication());
    }
}
