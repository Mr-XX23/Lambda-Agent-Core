package ai.lambda.ai.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link HttpClient} per connection setting, shared by every model client and generator.
 * A shared client keeps its connections open between requests, so later calls to the same
 * provider skip the TCP and TLS handshake, and HTTP/2 lets parallel calls (subagents, parallel
 * tools) share one connection instead of each opening its own. Separate clients per model client
 * would each pay that setup cost and keep their own threads.
 */
final class SharedHttpClients {

    private static final Map<String, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    private SharedHttpClients() {
    }

    static HttpClient get(Duration connectTimeout, boolean followRedirects) {
        String key = connectTimeout.toMillis() + (followRedirects ? ":follow" : ":stay");
        return CLIENTS.computeIfAbsent(key, k -> HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(connectTimeout)
                .followRedirects(followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
                .build());
    }
}
