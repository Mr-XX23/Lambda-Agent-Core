package ai.lambda.ai.gemini;

import ai.lambda.ai.core.HttpOptions;
import com.google.genai.Client;
import com.google.genai.types.HttpRetryOptions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the SDK client the same way for every Gemini class. */
final class GeminiClients {

    private static final List<Integer> RETRYABLE_STATUS = List.of(408, 429, 500, 502, 503, 504);

    private GeminiClients() {
    }

    /** @param baseUrl another API root, or null for Google's own */
    static Client create(String apiKey, HttpOptions options, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Gemini needs an API key");
        Objects.requireNonNull(options, "options must not be null");
        com.google.genai.types.HttpOptions.Builder http = com.google.genai.types.HttpOptions.builder()
                .retryOptions(HttpRetryOptions.builder()
                        .attempts(options.maxAttempts())
                        .initialDelay(options.initialBackoff().toMillis() / 1000.0)
                        .httpStatusCodes(RETRYABLE_STATUS));
        if (baseUrl != null) http.baseUrl(baseUrl);
        return Client.builder().apiKey(apiKey).httpOptions(http.build()).build();
    }

    /** Per-request options carrying extra body fields, or null when there are none. */
    static com.google.genai.types.HttpOptions extraBody(Map<String, Object> fields) {
        return fields.isEmpty() ? null : com.google.genai.types.HttpOptions.builder().extraBody(fields).build();
    }
}
