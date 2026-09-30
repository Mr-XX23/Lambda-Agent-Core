package ai.lambda.ai.client;

import ai.lambda.ai.core.HttpOptions;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Sends a request, retrying on network errors and on "try again later" status codes.
 */
final class HttpRetry {

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);
    /** The longest wait between two attempts. */
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private HttpRetry() {
    }

    /** The shared, connection-reusing client for these options (see {@link SharedHttpClients}). */
    static HttpClient newClient(HttpOptions options) {
        return SharedHttpClients.get(options.connectTimeout(), false);
    }

    /**
     * Returns the first non-retryable response, or the last response once attempts run out or
     * the provider asks (with Retry-After) for a wait longer than the request timeout.
     * Callers still need to check the status code. Network failures on the final attempt
     * are thrown as a RuntimeException.
     */
    static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                    HttpOptions options, String provider) {
        for (int attempt = 1; ; attempt++) {
            boolean lastAttempt = attempt >= options.maxAttempts();
            Duration delay;
            try {
                HttpResponse<T> response = client.send(request, handler);
                if (lastAttempt || !RETRYABLE_STATUS.contains(response.statusCode())) {
                    return response;
                }
                delay = backoff(options, attempt, response.headers().firstValue("Retry-After"));
                // A provider asking for a very long wait: hand the response back instead of blocking the caller.
                if (delay.compareTo(options.requestTimeout()) > 0) return response;
            } catch (HttpTimeoutException e) {
                // The request reached the provider but no answer came in time. Sending it again could
                // do (and bill) the work twice, for example generate an image twice, so give up.
                // A connect timeout is different: nothing was sent, so it is safe to try again.
                if (lastAttempt || !(e instanceof HttpConnectTimeoutException)) {
                    throw new RuntimeException(provider + " did not answer within "
                            + (e instanceof HttpConnectTimeoutException ? options.connectTimeout() + " (could not connect)"
                            : options.requestTimeout()), e);
                }
                delay = backoff(options, attempt, Optional.empty());
            } catch (IOException e) {
                if (lastAttempt) {
                    throw new RuntimeException("Failed to call " + provider + " after " + attempt + " attempt(s)", e);
                }
                delay = backoff(options, attempt, Optional.empty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while calling " + provider, e);
            }

            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting to retry " + provider, e);
            }
        }
    }

    static Duration backoff(HttpOptions options, int attempt, Optional<String> retryAfter) {
        if (retryAfter.isPresent()) {
            try {
                return Duration.ofSeconds(Math.max(0, Long.parseLong(retryAfter.get().trim())));
            } catch (NumberFormatException ignored) {
                // Retry-After can also be an HTTP date; fall back to exponential backoff.
            }
        }
        // Doubles each time, but never beyond MAX_BACKOFF (and the shift cannot overflow).
        Duration delay = options.initialBackoff().multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
