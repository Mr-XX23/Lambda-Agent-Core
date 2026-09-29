package ai.lambda.ai.client;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Sends a request, retrying on network errors and on "try again later" status codes.
 */
final class HttpRetry {

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    private HttpRetry() {
    }

    static HttpClient newClient(HttpOptions options) {
        return HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                .build();
    }

    /**
     * Returns the first non-retryable response, or the last response once attempts run out.
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

    private static Duration backoff(HttpOptions options, int attempt, Optional<String> retryAfter) {
        if (retryAfter.isPresent()) {
            try {
                return Duration.ofSeconds(Long.parseLong(retryAfter.get().trim()));
            } catch (NumberFormatException ignored) {
                // Retry-After can also be an HTTP date; fall back to exponential backoff.
            }
        }
        return options.initialBackoff().multipliedBy(1L << (attempt - 1));
    }
}
