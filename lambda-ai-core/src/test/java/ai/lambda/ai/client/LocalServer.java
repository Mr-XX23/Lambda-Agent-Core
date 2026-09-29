package ai.lambda.ai.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** A local HTTP server standing in for a provider API in tests. It records every request. */
final class LocalServer implements AutoCloseable {

    record Request(String method, String path, Map<String, List<String>> headers, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            List<String> values = headers.get(name);
            return values == null ? null : values.get(0);
        }
    }

    record Reply(int status, String contentType, byte[] body) {
        static Reply json(String json) {
            return new Reply(200, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }

        static Reply bytes(String contentType, byte[] body) {
            return new Reply(200, contentType, body);
        }

        static Reply sse(String... events) {
            StringBuilder sb = new StringBuilder();
            for (String e : events) sb.append(e.startsWith(":") ? e : "data: " + e).append("\n\n");
            return new Reply(200, "text/event-stream", sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    LocalServer(Function<Request, Reply> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> handle(exchange, handler));
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange, Function<Request, Reply> handler) throws IOException {
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                Map.copyOf(exchange.getRequestHeaders()), exchange.getRequestBody().readAllBytes());
        requests.add(request);
        Reply reply = handler.apply(request);
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), reply.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(reply.body());
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
