package ai.lambda.ai.client;

import ai.lambda.ai.core.Downloads;
import ai.lambda.ai.core.HttpOptions;
import ai.lambda.ai.core.Media;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Small HTTP helper for the generation clients: JSON requests, binary responses, multipart
 * uploads and downloads, all with {@link HttpRetry}'s timeouts and retries, and errors that
 * include the provider's message.
 */
final class JsonHttp {

    /** How much of an error body goes into an exception message. */
    private static final int MAX_ERROR_CHARS = 2_000;

    /** A binary response and its content type. */
    record Binary(byte[] data, String contentType) {
    }

    private final String provider;
    private final HttpOptions options;
    private final Map<String, String> headers;
    private final HttpClient client;

    JsonHttp(String provider, HttpOptions options, Map<String, String> headers) {
        this.provider = Objects.requireNonNull(provider);
        this.options = Objects.requireNonNull(options);
        this.headers = Map.copyOf(headers);
        // Downloads (for example generated videos) may redirect to a storage host.
        this.client = SharedHttpClients.get(options.connectTimeout(), true);
    }

    static Map<String, String> bearer(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri).timeout(options.requestTimeout());
        headers.forEach(builder::header);
        return builder;
    }

    JSONObject postJson(URI uri, JSONObject body) {
        HttpRequest request = request(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        return new JSONObject(check(send(request, HttpResponse.BodyHandlers.ofString())).body());
    }

    JSONObject getJson(URI uri) {
        HttpRequest request = request(uri).GET().build();
        return new JSONObject(check(send(request, HttpResponse.BodyHandlers.ofString())).body());
    }

    Binary postForBytes(URI uri, JSONObject body) {
        HttpRequest request = request(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        return binary(send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    /** Downloads a file the provider pointed to, safely (see {@link Downloads}). */
    Binary download(URI uri) {
        return download(uri, Downloads.MAX_BYTES);
    }

    // The limit is a parameter so tests can use a small one.
    Binary download(URI uri, long maxBytes) {
        Downloads.File file = Downloads.fetch(provider, uri, options, maxBytes);
        return new Binary(file.data(), file.contentType());
    }

    static void requireSafeDownload(URI uri) {
        Downloads.requireSafe(uri);
    }

    /** Keeps error text short enough for an exception message and a log line. */
    static String shorten(String text) {
        if (text == null || text.length() <= MAX_ERROR_CHARS) return text;
        return text.substring(0, MAX_ERROR_CHARS) + "... (" + (text.length() - MAX_ERROR_CHARS) + " more characters)";
    }

    /** Sends {@code multipart/form-data} with text fields and one file. */
    JSONObject postMultipart(URI uri, Map<String, String> fields, String fileField, Media file) {
        String boundary = "lambda-" + UUID.randomUUID();
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field.getKey())
                    .append("\"\r\n\r\n").append(field.getValue()).append("\r\n");
        }
        String fileName = file.name() != null ? file.name() : "file." + file.fileExtension();
        head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(fileField)
                .append("\"; filename=\"").append(fileName.replace("\"", "")).append("\"\r\nContent-Type: ")
                .append(file.mimeType()).append("\r\n\r\n");

        // Sent in three parts, so the file is not copied into one more large array.
        HttpRequest request = request(uri).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofString(head.toString(), StandardCharsets.UTF_8),
                        file.readBytes(HttpRequest.BodyPublishers::ofByteArray),
                        HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8)))
                .build();
        return new JSONObject(check(send(request, HttpResponse.BodyHandlers.ofString())).body());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return HttpRetry.send(client, request, handler, options, provider);
    }

    private HttpResponse<String> check(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException(provider + " error: " + response.statusCode() + " " + shorten(response.body()));
        }
        return response;
    }

    private Binary binary(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException(provider + " error: " + response.statusCode() + " "
                    + shorten(new String(response.body(), StandardCharsets.UTF_8)));
        }
        String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return new Binary(response.body(), type.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT));
    }
}
