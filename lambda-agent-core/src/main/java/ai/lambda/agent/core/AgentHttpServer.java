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

/** Minimal JDK HTTP bridge; applications can place auth and TLS in their edge. */
public final class AgentHttpServer implements AutoCloseable {
    private final Agent agent;
    private final HttpServer server;
    private final ExecutorService executor;

    public AgentHttpServer(Agent agent, InetSocketAddress address) throws IOException {
        this.agent = Objects.requireNonNull(agent);
        this.server = HttpServer.create(address, 0);
        server.createContext("/agent/run", this::handleRun);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
    }

    public void start() { server.start(); }

    private void handleRun(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, new JSONObject().put("error", "POST required"));
            return;
        }
        try {
            JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            String sessionId = request.getString("sessionId");
            String input = request.getString("input");
            AgentResult result = agent.run(sessionId, input);
            respond(exchange, 200, new JSONObject()
                    .put("runId", result.getRunId())
                    .put("text", result.getFinalText())
                    .put("iterations", result.getIterations()));
        } catch (RuntimeException error) {
            respond(exchange, 400, new JSONObject().put("error", error.getMessage()));
        }
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
