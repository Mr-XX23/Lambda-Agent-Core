package ai.lambda.ai.client;

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

    Binary download(URI uri) {
        return binary(send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    /** Sends {@code multipart/form-data} with text fields and one file. */
    JSONObject postMultipart(URI uri, Map<String, String> fields, String fileField, Media file) {
        String boundary = "lambda-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n");
        }
        String fileName = file.name() != null ? file.name() : "file." + file.fileExtension();
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                + "\"; filename=\"" + fileName.replace("\"", "") + "\"\r\nContent-Type: " + file.mimeType() + "\r\n\r\n");
        body.writeBytes(file.data());
        write(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest request = request(uri).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return new JSONObject(check(send(request, HttpResponse.BodyHandlers.ofString())).body());
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return HttpRetry.send(client, request, handler, options, provider);
    }

    private HttpResponse<String> check(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException(provider + " error: " + response.statusCode() + " " + response.body());
        }
        return response;
    }

    private Binary binary(HttpResponse<byte[]> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException(provider + " error: " + response.statusCode() + " "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        return new Binary(response.body(), type.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT));
    }
}
