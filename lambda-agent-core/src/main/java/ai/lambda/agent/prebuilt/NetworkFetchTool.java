package ai.lambda.agent.prebuilt;

import ai.lambda.agent.core.*;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;

public final class NetworkFetchTool implements AgentTool {
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private final Set<String> allowedHosts;
    public NetworkFetchTool(Set<String> allowedHosts) { this.allowedHosts = Set.copyOf(allowedHosts); }
    @Override public String getName() { return "fetch_url"; }
    @Override public String getDescription() { return "Fetches an HTTPS URL from an allowlisted host."; }
    @Override public String getJsonSchema() { return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}"; }
    @Override public ToolPolicy getPolicy() { return new ToolPolicy(true, Duration.ofSeconds(10), 32768, Set.of(ToolCapability.NETWORK)); }
    @Override public TypedToolInput<?> getTypedInputSchema() {
        return json -> {
            URI uri = URI.create(new JSONObject(json).getString("url"));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHosts.contains(uri.getHost())) {
                throw new SecurityException("URL scheme or host is not allowed");
            }
            return uri;
        };
    }
    @Override public ToolResult execute(ToolInvocationContext context) throws Exception {
        URI uri = (URI) getTypedInputSchema().parse(context.getArgumentsJson());
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new SecurityException("Network response exceeds the 32 KiB sandbox limit");
        }
        return ToolResult.of(new String(response.body()));
    }
}
