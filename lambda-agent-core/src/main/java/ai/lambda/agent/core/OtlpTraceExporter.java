package ai.lambda.agent.core;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Lightweight OTLP/HTTP JSON hook; applications can replace it with the OpenTelemetry SDK. */
public final class OtlpTraceExporter implements TraceExporter, AutoCloseable {
    private final HttpClient client;
    private final URI endpoint;
    private final String authorization;

    public OtlpTraceExporter(URI endpoint) {
        this(endpoint, null);
    }
    public OtlpTraceExporter(URI endpoint, String authorization) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.authorization = authorization;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    @Override public void export(TraceEvent event) {
        JSONObject body = new JSONObject()
                .put("traceId", event.traceId()).put("runId", event.runId())
                .put("sessionId", event.sessionId()).put("name", event.name())
                .put("type", event.type().name()).put("durationMillis", event.durationMillis())
                .put("attributes", new JSONObject(event.attributes()));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (authorization != null && !authorization.isBlank()) request.header("Authorization", authorization);
        client.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                .exceptionally(error -> null);
    }
    @Override public void close() {}
}
