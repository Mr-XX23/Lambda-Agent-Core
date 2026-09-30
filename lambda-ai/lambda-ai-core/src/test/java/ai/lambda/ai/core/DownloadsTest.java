package ai.lambda.ai.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DownloadsTest {

    private static final HttpOptions QUICK = new HttpOptions(Duration.ofSeconds(2), Duration.ofMillis(400), 1, Duration.ZERO);

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/file.png", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = new byte[3_000];
            exchange.getResponseHeaders().add("Content-Type", "image/png; charset=binary");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> {
            byte[] body = "not here".getBytes();
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(new byte[100]);
            out.flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                // server stopping
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path + "?signature=secret-token");
    }

    @Test
    void fetchesTheFileWithoutCredentials() {
        Downloads.File file = Downloads.fetch("Acme", url("/file.png"), QUICK);

        assertEquals(3_000, file.data().length);
        assertEquals("image/png", file.contentType());
        assertNull(authorization.get());
    }

    @Test
    void refusesFilesLargerThanAllowed() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> Downloads.fetch("Acme", url("/file.png"), QUICK, 1_000));
        assertTrue(e.getMessage().contains("larger than 1000 bytes"), e.getMessage());
    }

    @Test
    void errorsNameTheHostButNotTheSignedUrl() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> Downloads.fetch("Acme", url("/missing"), QUICK));
        assertTrue(e.getMessage().contains("404") && e.getMessage().contains("127.0.0.1"), e.getMessage());
        assertFalse(e.getMessage().contains("secret-token"), e.getMessage());
    }

    @Test
    void aStalledTransferIsAbandoned() {
        long start = System.nanoTime();
        RuntimeException e = assertThrows(RuntimeException.class, () -> Downloads.fetch("Acme", url("/stall"), QUICK));
        assertTrue(e.getMessage().contains("stopped sending data"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 5_000);
    }

    @Test
    void onlyHttpsOrThisMachine() {
        assertThrows(SecurityException.class, () -> Downloads.requireSafe(URI.create("http://example.com/a.png")));
        assertThrows(SecurityException.class, () -> Downloads.requireSafe(URI.create("ftp://example.com/a.png")));
        assertThrows(SecurityException.class, () -> Downloads.requireSafe(URI.create("file:///etc/passwd")));
        assertDoesNotThrow(() -> Downloads.requireSafe(URI.create("https://cdn.example.com/a.png")));
        assertDoesNotThrow(() -> Downloads.requireSafe(URI.create("http://localhost:8080/a.png")));
    }

    @Test
    void callsGetAtLeastAnHour() {
        assertEquals(Duration.ofMinutes(10), HttpOptions.defaults().requestTimeout());
        assertEquals(HttpOptions.MAX_CALL, HttpOptions.defaults().callTimeout());
        HttpOptions patient = new HttpOptions(Duration.ofSeconds(1), Duration.ofHours(2), 1, Duration.ZERO);
        assertEquals(Duration.ofHours(2), patient.callTimeout(), "never shorter than the request timeout");
    }

    @Test
    void theWatchdogLeavesAnActiveStreamAlone() throws Exception {
        AtomicReference<Boolean> closed = new AtomicReference<>(false);
        try (StreamWatchdog watchdog = new StreamWatchdog("Acme", Duration.ofMillis(200), () -> closed.set(true))) {
            for (int i = 0; i < 10; i++) {
                Thread.sleep(50);
                watchdog.activity();
            }
            watchdog.check();
            assertFalse(closed.get());
            assertSame(IllegalStateException.class, watchdog.explain(new IllegalStateException("x")).getClass(),
                    "other failures pass through unchanged");
        }
    }
}
