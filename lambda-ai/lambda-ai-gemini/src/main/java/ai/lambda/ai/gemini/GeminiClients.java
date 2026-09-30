package ai.lambda.ai.gemini;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.ProviderClients;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpRetryOptions;

import okhttp3.OkHttpClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the SDK client the same way for every Gemini class. */
final class GeminiClients {

    private static final List<Integer> RETRYABLE_STATUS = List.of(408, 429, 500, 502, 503, 504);

    private GeminiClients() {
    }

    /**
     * The shared SDK client for these settings (see {@link ProviderClients}).
     *
     * @param baseUrl another API root, or null for Google's own
     */
    static Client create(String apiKey, HttpOptions options, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Gemini needs an API key");
        Objects.requireNonNull(options, "options must not be null");
        return ProviderClients.shared("gemini", apiKey, options, baseUrl, () -> build(apiKey, options, baseUrl));
    }

    private static Client build(String apiKey, HttpOptions options, String baseUrl) {
        com.google.genai.types.HttpOptions.Builder http = com.google.genai.types.HttpOptions.builder()
                .retryOptions(HttpRetryOptions.builder()
                        .attempts(options.maxAttempts())
                        .initialDelay(options.initialBackoff().toMillis() / 1000.0)
                        .httpStatusCodes(RETRYABLE_STATUS));
        if (baseUrl != null) http.baseUrl(baseUrl);
        // The SDK's own HTTP client has no timeouts. This one gives up on a connection that cannot
        // be opened, and on a response that stops sending data for the request timeout; the read
        // timeout counts from the last bytes received, so long streamed answers are not cut off.
        OkHttpClient okHttp = new OkHttpClient.Builder()
                .connectTimeout(options.connectTimeout())
                .readTimeout(options.requestTimeout())
                .writeTimeout(options.requestTimeout())
                .build();
        return Client.builder().apiKey(apiKey).httpOptions(http.build())
                .clientOptions(ClientOptions.builder().customHttpClient(okHttp).build()).build();
    }

    /**
     * Per-request options: a limit for the whole call (the SDK has no other timeout, so without it a
     * call could wait forever) and any extra body fields.
     */
    static com.google.genai.types.HttpOptions requestOptions(java.time.Duration timeout, Map<String, Object> extraBody) {
        com.google.genai.types.HttpOptions.Builder options = com.google.genai.types.HttpOptions.builder()
                .timeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        if (!extraBody.isEmpty()) options.extraBody(extraBody);
        return options.build();
    }

    /** Ids Lambda made up (Gemini sent none) are not sent back to Gemini. */
    /** A read that timed out, somewhere in the failure's causes. */
    static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    static boolean madeUp(String id) {
        return id == null || id.startsWith(GENERATED_ID) || UUID_ID.matcher(id).matches();
    }

    static final String GENERATED_ID = "lambda-";
    // Earlier versions made up plain UUIDs.
    private static final java.util.regex.Pattern UUID_ID =
            java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
}
