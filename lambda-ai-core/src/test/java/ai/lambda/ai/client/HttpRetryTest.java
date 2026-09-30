package ai.lambda.ai.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpRetryTest {

    /** One scripted reply: a status and an optional Retry-After header. */
    private record Reply(int status, String retryAfter) {
    }

    private final Deque<Reply> script = new ArrayDeque<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            Reply reply;
            synchronized (script) {
                reply = script.size() > 1 ? script.poll() : script.peek(); // the last reply repeats
            }
            if (reply.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
            byte[] body = ("status " + reply.status()).getBytes();
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static HttpOptions options(int attempts, Duration backoff) {
        return new HttpOptions(Duration.ofSeconds(2), Duration.ofSeconds(5), attempts, backoff);
    }

    private HttpResponse<String> send(HttpOptions options) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).build();
        return HttpRetry.send(HttpRetry.newClient(options), request, HttpResponse.BodyHandlers.ofString(), options, "Test");
    }

    private void reply(int status, String retryAfter) {
        script.add(new Reply(status, retryAfter));
    }

    @Test
    void retriesTemporaryFailuresUntilSuccess() {
        reply(503, null);
        reply(429, null);
        reply(200, null);

        HttpResponse<String> response = send(options(3, Duration.ofMillis(1)));

        assertEquals(200, response.statusCode());
        assertEquals(3, requests.get());
    }

    @Test
    void returnsTheLastResponseWhenAttemptsRunOut() {
        reply(500, null);

        HttpResponse<String> response = send(options(3, Duration.ofMillis(1)));

        assertEquals(500, response.statusCode());
        assertEquals("status 500", response.body());
        assertEquals(3, requests.get());
    }

    @Test
    void doesNotRetryErrorsThatWillNotGoAway() {
        for (int status : new int[]{400, 401, 404}) {
            script.clear();
            requests.set(0);
            reply(status, null);

            assertEquals(status, send(options(3, Duration.ofMillis(1))).statusCode());
            assertEquals(1, requests.get(), "status " + status);
        }
    }

    @Test
    void oneAttemptMeansNoRetry() {
        reply(503, null);
        assertEquals(503, send(options(1, Duration.ofMillis(1))).statusCode());
        assertEquals(1, requests.get());
    }

    @Test
    void waitsAsLongAsRetryAfterSaysInsteadOfTheBackoff() {
        reply(429, "0");
        reply(200, null);
        long start = System.nanoTime();

        HttpResponse<String> response = send(options(2, Duration.ofSeconds(30))); // backoff would be 30 s

        assertEquals(200, response.statusCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "Retry-After: 0 means retry now");
    }

    @Test
    void aVeryLongRetryAfterReturnsTheResponseInsteadOfWaiting() {
        reply(429, "86400");
        long start = System.nanoTime();

        HttpResponse<String> response = send(options(3, Duration.ofMillis(1)));

        assertEquals(429, response.statusCode());
        assertEquals(1, requests.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    @Test
    void unusableRetryAfterValuesFallBackSafely() {
        reply(503, "Wed, 21 Oct 2026 07:28:00 GMT"); // a date: the backoff is used
        reply(503, "-5");                            // negative: retry now
        reply(200, null);

        assertEquals(200, send(options(3, Duration.ofMillis(1))).statusCode());
        assertEquals(3, requests.get());
    }

    @Test
    void networkFailuresAreRetriedThenReported() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        HttpOptions options = options(2, Duration.ofMillis(1));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closedPort + "/")).build();

        RuntimeException error = assertThrows(RuntimeException.class, () -> HttpRetry.send(
                HttpRetry.newClient(options), request, HttpResponse.BodyHandlers.ofString(), options, "Acme"));

        assertEquals("Failed to call Acme after 2 attempt(s)", error.getMessage());
        assertInstanceOf(IOException.class, error.getCause());
    }

    @Test
    void anInterruptedWaitStopsRetrying() {
        reply(503, null);
        Thread.currentThread().interrupt();
        try {
            RuntimeException error = assertThrows(RuntimeException.class, () -> send(options(3, Duration.ofSeconds(30))));
            assertTrue(error.getMessage().contains("Interrupted"), error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for the caller");
        } finally {
            Thread.interrupted(); // clear it for the next test
        }
    }

    @Test
    void optionsValidateTheirSettings() {
        assertThrows(IllegalArgumentException.class, () -> options(0, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new HttpOptions(null, Duration.ZERO, 1, Duration.ZERO));
        assertEquals(3, HttpOptions.defaults().maxAttempts());
    }
}
