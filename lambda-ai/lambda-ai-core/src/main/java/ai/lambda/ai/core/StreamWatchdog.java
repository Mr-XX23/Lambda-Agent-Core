package ai.lambda.ai.core;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Stops a response body that goes quiet. The JDK client's request timeout ends once the headers
 * arrive, so a stream that then stalls, or a connection dropped without notice, would block its
 * reader forever. The watchdog closes the body after {@code idle} without data, which ends the
 * read; {@link #check()} then turns that into a clear error instead of a cut-off answer.
 *
 * <pre>
 * try (var body = ...; var watchdog = new StreamWatchdog("Acme", idle, body)) {
 *     try {
 *         for (...each chunk...) { watchdog.activity(); ... }
 *     } catch (RuntimeException failure) {
 *         throw watchdog.explain(failure);
 *     }
 *     watchdog.check();
 * }
 * </pre>
 * For provider modules; applications do not need it.
 */
public final class StreamWatchdog implements AutoCloseable {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lambda-stream-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final String provider;
    private final Duration idle;
    private final AutoCloseable body;
    private final ScheduledFuture<?> checks;
    private volatile long lastActivity = System.nanoTime();
    private volatile boolean stalled;

    public StreamWatchdog(String provider, Duration idle, AutoCloseable body) {
        this.provider = provider;
        this.idle = idle;
        this.body = body;
        long every = Math.max(10, Math.min(idle.toMillis() / 4, 1_000));
        this.checks = TIMER.scheduleAtFixedRate(this::closeIfQuiet, every, every, TimeUnit.MILLISECONDS);
    }

    /** Call whenever data arrives. */
    public void activity() {
        lastActivity = System.nanoTime();
    }

    private void closeIfQuiet() {
        if (stalled || System.nanoTime() - lastActivity < idle.toNanos()) return;
        stalled = true;
        checks.cancel(false);
        try {
            body.close();
        } catch (Exception ignored) {
            // the reader sees the closed body either way
        }
    }

    /** Throws if the body was closed for going quiet; call after reading ends. */
    public void check() {
        if (stalled) throw stalledError(null);
    }

    /**
     * The error to throw when reading failed: closing a body makes the read fail, and that
     * failure is reported as the stall it really is.
     */
    public RuntimeException explain(Exception failure) {
        if (stalled) return stalledError(failure);
        return failure instanceof RuntimeException runtime ? runtime : new RuntimeException(failure);
    }

    private RuntimeException stalledError(Exception cause) {
        return new RuntimeException(provider + " stopped sending data for " + idle.toMillis() / 1000.0
                + " s; the response was abandoned (raise the request timeout for slower models)", cause);
    }

    @Override
    public void close() {
        checks.cancel(false);
    }
}
