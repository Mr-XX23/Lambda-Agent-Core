package ai.lambda.ai.client;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeout and retry settings shared by the HTTP model clients.
 *
 * @param connectTimeout max time to open a connection
 * @param requestTimeout max time to wait for the response to start arriving
 * @param maxAttempts    total tries per request (1 means no retries)
 * @param initialBackoff wait before the first retry; doubles on each retry after that
 */
public record HttpOptions(Duration connectTimeout, Duration requestTimeout, int maxAttempts, Duration initialBackoff) {

    public HttpOptions {
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        Objects.requireNonNull(initialBackoff, "initialBackoff must not be null");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");
    }

    public static HttpOptions defaults() {
        return new HttpOptions(Duration.ofSeconds(10), Duration.ofSeconds(60), 3, Duration.ofMillis(500));
    }
}
