package ai.lambda.agent.core;

import java.util.Objects;

public record HttpServerConfig(String bearerToken, int maxRequestBytes, int maxResponseBytes,
                               int maxConcurrentRequests) {
    public HttpServerConfig(String bearerToken, int maxRequestBytes, int maxResponseBytes) {
        this(bearerToken, maxRequestBytes, maxResponseBytes, 64);
    }

    public HttpServerConfig {
        if (maxRequestBytes <= 0 || maxResponseBytes <= 0 || maxConcurrentRequests <= 0) {
            throw new IllegalArgumentException("HTTP limits must be positive");
        }
    }

    public static HttpServerConfig defaults() {
        return new HttpServerConfig(null, 64 * 1024, 128 * 1024, 64);
    }

    public boolean requiresAuthentication() {
        return bearerToken != null && !bearerToken.isBlank();
    }
}
