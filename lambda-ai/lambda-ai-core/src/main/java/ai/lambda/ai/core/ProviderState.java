package ai.lambda.ai.core;

import java.util.Objects;

/**
 * Data a provider needs sent back exactly as it was received, stored on the assistant message
 * it came with (for example Claude's signed thinking blocks). Only the client whose
 * {@code provider} name matches reads it; other clients ignore it.
 *
 * @param provider the client that produced it, such as {@code "anthropic"}
 * @param json     the provider's raw content, as JSON
 */
public record ProviderState(String provider, String json) {
    public ProviderState {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(json, "json must not be null");
    }
}
