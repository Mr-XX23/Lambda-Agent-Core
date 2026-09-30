package ai.lambda.ai.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeout and retry settings shared by the HTTP model clients.
 *
 * @param connectTimeout max time to open a connection
 * @param requestTimeout the longest wait for the provider to start answering or, while an answer
 *                       streams in, to send more of it. Reasoning models can think for minutes
 *                       before the first word, so this is 10 minutes by default. A single call is
 *                       also stopped after {@link #MAX_CALL}.
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

    /** The longest a single call, streamed answer included, may take (at least the request timeout). */
    public static final Duration MAX_CALL = Duration.ofHours(1);

    public static HttpOptions defaults() {
        return new HttpOptions(Duration.ofSeconds(10), Duration.ofMinutes(10), 3, Duration.ofMillis(500));
    }

    /** The limit for a whole call: {@link #MAX_CALL}, or the request timeout if that is longer. */
    public Duration callTimeout() {
        return requestTimeout.compareTo(MAX_CALL) > 0 ? requestTimeout : MAX_CALL;
    }
}
