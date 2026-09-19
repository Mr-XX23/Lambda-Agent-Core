package ai.lambda.agent.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.Map;

/** Minimal JDK HTTP bridge; applications can place auth and TLS in their edge. */
public final class AgentHttpServer implements AutoCloseable {
    private final Agent agent;
    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpServerConfig config;
    private final Map<String, Workflow> workflows;

    public AgentHttpServer(Agent agent, InetSocketAddress address) throws IOException {
        this(agent, address, HttpServerConfig.defaults(), Map.of());
    }

    public AgentHttpServer(Agent agent, InetSocketAddress address, HttpServerConfig config) throws IOException {
        this(agent, address, config, Map.of());
    }

    public AgentHttpServer(Agent agent, InetSocketAddress address, HttpServerConfig config,
                           Map<String, Workflow> workflows) throws IOException {
        this.agent = Objects.requireNonNull(agent);
        this.config = Objects.requireNonNull(config);
        this.workflows = Map.copyOf(workflows);
        this.server = HttpServer.create(address, 0);
        server.createContext("/agent/run", this::handleRun);
        server.createContext("/workflow/run", this::handleWorkflow);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
    }

    private void handleWorkflow(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            respond(exchange, 401, new JSONObject().put("error", "authentication required"));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, new JSONObject().put("error", "POST required"));
            return;
        }
        try {
            byte[] bytes = exchange.getRequestBody().readNBytes(config.maxRequestBytes() + 1);
            if (bytes.length > config.maxRequestBytes()) {
                respond(exchange, 413, new JSONObject().put("error", "request too large"));
                return;
            }
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Workflow workflow = workflows.get(request.getString("workflow"));
            if (workflow == null) {
                respond(exchange, 404, new JSONObject().put("error", "workflow not found"));
                return;
            }
            WorkflowResult result = workflow.run(request.getString("executionId"),
                    request.optJSONObject("state") == null ? Map.of() : request.getJSONObject("state").toMap(),
                    new CancellationToken());
            respond(exchange, 200, new JSONObject().put("executionId", result.executionId())
                    .put("status", result.status().name()).put("nextStep", result.checkpoint().nextStep()));
        } catch (org.json.JSONException | IllegalArgumentException error) {
            respond(exchange, 400, new JSONObject().put("error", "invalid workflow request"));
        } catch (RuntimeException error) {
            respond(exchange, 500, new JSONObject().put("error", "workflow execution failed"));
        }
    }

    public void start() { server.start(); }

    private void handleRun(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            respond(exchange, 401, new JSONObject().put("error", "authentication required"));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, new JSONObject().put("error", "POST required"));
            return;
        }
        try {
            byte[] requestBytes = exchange.getRequestBody().readNBytes(config.maxRequestBytes() + 1);
            if (requestBytes.length > config.maxRequestBytes()) {
                respond(exchange, 413, new JSONObject().put("error", "request too large"));
                return;
            }
            JSONObject request = new JSONObject(new String(requestBytes,
                    StandardCharsets.UTF_8));
            String sessionId = request.getString("sessionId");
            String input = request.getString("input");
            AgentResult result = agent.run(sessionId, input);
            JSONObject response = new JSONObject()
                    .put("runId", result.getRunId())
                    .put("text", result.getFinalText())
                    .put("iterations", result.getIterations());
            if (response.toString().getBytes(StandardCharsets.UTF_8).length > config.maxResponseBytes()) {
                respond(exchange, 500, new JSONObject().put("error", "response exceeds configured limit"));
            } else if ("text/event-stream".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Accept"))) {
                respondSse(exchange, response);
            } else {
                respond(exchange, 200, response);
            }
        } catch (org.json.JSONException error) {
            respond(exchange, 400, new JSONObject().put("error", "invalid JSON request"));
        } catch (RuntimeException error) {
            respond(exchange, 500, new JSONObject().put("error", "agent execution failed"));
        }
    }

    private boolean authorized(HttpExchange exchange) {
        if (!config.requiresAuthentication()) return true;
        String value = exchange.getRequestHeaders().getFirst("Authorization");
        return value != null && value.equals("Bearer " + config.bearerToken());
    }

    private static void respondSse(HttpExchange exchange, JSONObject body) throws IOException {
        byte[] payload = ("event: result\ndata: " + body + "\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, payload.length);
        try (var output = exchange.getResponseBody()) { output.write(payload); }
    }

    private static void respond(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
