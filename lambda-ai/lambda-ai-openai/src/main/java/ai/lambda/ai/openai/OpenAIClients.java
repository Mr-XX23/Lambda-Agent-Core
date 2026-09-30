package ai.lambda.ai.openai;

import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.ProviderClients;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.MultipartField;
import com.openai.core.Timeout;
import com.openai.core.http.HttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Builds the SDK client the same way for every OpenAI class, and small helpers they share. */
final class OpenAIClients {

    private OpenAIClients() {
    }

    /**
     * The shared SDK client for these settings (see {@link ProviderClients}).
     *
     * @param baseUrl another API root, or null for OpenAI's own
     */
    static OpenAIClient create(String apiKey, HttpOptions options, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("OpenAI needs an API key");
        Objects.requireNonNull(options, "options must not be null");
        return ProviderClients.shared("openai", apiKey, options, baseUrl, () -> build(apiKey, options, baseUrl));
    }

    private static OpenAIClient build(String apiKey, HttpOptions options, String baseUrl) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(options.maxAttempts() - 1)
                // "read" bounds each wait for more bytes; "request" the whole call. The SDK's own
                // limit for a whole call is 10 minutes, which would cut long streamed answers off.
                .timeout(Timeout.builder().connect(options.connectTimeout()).read(options.requestTimeout())
                        .request(options.callTimeout()).build());
        if (baseUrl != null) builder.baseUrl(baseUrl);
        return builder.build();
    }

    /** Adds provider-specific request fields as they are. */
    static void putOptions(Map<String, Object> options, BiConsumer<String, JsonValue> put) {
        options.forEach((key, value) -> put.accept(key, JsonValue.from(value)));
    }

    /** A file for a multipart upload, with the name and type the API uses to recognise it. */
    static <T> MultipartField<T> file(T value, String fileName, String contentType) {
        return MultipartField.<T>builder().value(value).filename(fileName).contentType(contentType).build();
    }

    static InputStream stream(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    /** Reads a binary response (audio, video) fully and closes it. */
    static byte[] bytes(HttpResponse response) {
        try (response; InputStream body = response.body()) {
            return body.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read OpenAI's response", e);
        }
    }
}
