package ai.lambda.ai.client;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.generation.ImageRequest;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Stalls, timeouts, unsafe downloads and unusual replies. */
class HardeningTest {

    private static final HttpOptions QUICK = new HttpOptions(Duration.ofSeconds(2), Duration.ofMillis(400), 3, Duration.ofMillis(1));

    /** A server whose handler can write part of a reply and then stall. */
    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void aStreamThatStallsIsAbandonedWithAClearError() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(10_000); // then silence, with the connection still open
            } catch (InterruptedException ignored) {
                // server stopping
            }
        });
        try {
            var client = new OpenAICompatibleModelClient(OpenAICompatibleProvider.OPENROUTER.withBaseUrl(url(server) + "/v1"), "k", "m", QUICK);
            long start = System.nanoTime();

            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> client.streamChat(List.of(new Message(Role.USER, "hi", null)), List.of(), null));

            long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(e.getMessage().contains("stopped sending data"), e.getMessage());
            assertTrue(millis < 5_000, "gave up after " + millis + " ms, not after the provider woke up");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRequestThatTimesOutIsNotSentAgain() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(3_000); // the work takes longer than the client waits
            } catch (InterruptedException ignored) {
                // server stopping
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            var images = new OpenAICompatibleImageGenerator(OpenAICompatibleProvider.OPENAI.withBaseUrl(url(server) + "/v1"), "k", "img", QUICK);

            RuntimeException e = assertThrows(RuntimeException.class, () -> images.generateImages(ImageRequest.of("a fox")));

            assertTrue(e.getMessage().contains("did not answer within"), e.getMessage());
            assertEquals(1, requests.get(), "a timed-out generation is not sent (and billed) again");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void downloadsCarryNoKeyAndRefuseUnsafeOrHugeFiles() throws Exception {
        AtomicInteger withKey = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (exchange.getRequestHeaders().containsKey("Authorization")) withKey.incrementAndGet();
            byte[] body = new byte[5_000];
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        try {
            JsonHttp http = new JsonHttp("Test", QUICK, JsonHttp.bearer("secret-key"));

            assertEquals(5_000, http.download(URI.create(url(server) + "/file.png")).data().length);
            assertEquals(0, withKey.get(), "the API key is not sent to the file's host");

            RuntimeException tooBig = assertThrows(RuntimeException.class, () -> http.download(URI.create(url(server) + "/big.mp4"), 1_000));
            assertTrue(tooBig.getMessage().contains("larger than 1000 bytes"), tooBig.getMessage());

            assertThrows(SecurityException.class, () -> http.download(URI.create("http://example.com/file.png")));
            assertThrows(SecurityException.class, () -> http.download(URI.create("file:///etc/passwd")));
            assertDoesNotThrow(() -> JsonHttp.requireSafeDownload(URI.create("https://cdn.example.com/a.png")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void backoffIsCappedAndCannotOverflow() {
        HttpOptions options = new HttpOptions(Duration.ofSeconds(1), Duration.ofSeconds(1), 100, Duration.ofMillis(500));
        assertEquals(Duration.ofMillis(500), HttpRetry.backoff(options, 1, Optional.empty()));
        assertEquals(Duration.ofSeconds(1), HttpRetry.backoff(options, 2, Optional.empty()));
        assertEquals(HttpRetry.MAX_BACKOFF, HttpRetry.backoff(options, 20, Optional.empty()));
        assertEquals(HttpRetry.MAX_BACKOFF, HttpRetry.backoff(options, 99, Optional.empty()), "no overflow for many attempts");
    }

    @Test
    void answersSentAsListsOfPartsAreReadAsText() {
        JSONArray magistral = new JSONArray()
                .put(new org.json.JSONObject().put("type", "thinking").put("thinking", "let me see"))
                .put(new org.json.JSONObject().put("type", "text").put("text", "The answer"))
                .put(" is 4.");
        assertEquals("The answer is 4.", OpenAICompatibleModelClient.text(magistral));
        assertEquals("plain", OpenAICompatibleModelClient.text("plain"));
        assertEquals("", OpenAICompatibleModelClient.text(org.json.JSONObject.NULL));
    }

    @Test
    void errorsInUnusualShapesAreReportedClearly() throws Exception {
        try (var server = new LocalServer(r -> LocalServer.Reply.json("{\"error\":\"model is overloaded\"}"))) {
            var client = new OpenAICompatibleModelClient(OpenAICompatibleProvider.OPENROUTER.withBaseUrl(server.url() + "/v1"), "k", "m", QUICK);
            RuntimeException e = assertThrows(RuntimeException.class, () -> client.chat(List.of(new Message(Role.USER, "hi", null)), List.of()));
            assertTrue(e.getMessage().contains("model is overloaded"), e.getMessage());
        }
        try (var server = new LocalServer(r -> LocalServer.Reply.json("{\"choices\":[]}"))) {
            var images = new OpenRouterImageGenerator(OpenAICompatibleProvider.OPENROUTER.withBaseUrl(server.url() + "/v1"), "k", "m", QUICK);
            RuntimeException e = assertThrows(RuntimeException.class, () -> images.generateImage("a fox"));
            assertTrue(e.getMessage().contains("returned no answer"), e.getMessage());
        }
    }

    @Test
    void headerValuesAreNotPrinted() {
        var gateway = OpenAICompatibleProvider.custom("Gateway", "https://gw.example.com/v1").withHeaders(Map.of("api-key", "super-secret"));
        assertFalse(gateway.toString().contains("super-secret"), gateway.toString());
        assertTrue(gateway.toString().contains("api-key"));
    }
}
