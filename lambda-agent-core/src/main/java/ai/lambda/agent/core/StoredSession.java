package ai.lambda.agent.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A session as a {@link SessionDatabase} stores it.
 *
 * @param version      increases by one on every save
 * @param messages     the messages as JSON strings, in order
 * @param metadataJson the metadata as one JSON object string
 * @param media        media bytes by name
 */
public record StoredSession(long version, List<String> messages, String metadataJson, Map<String, byte[]> media) {

    public StoredSession {
        if (version < 1) throw new IllegalArgumentException("A stored session has version 1 or more");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        media = Map.copyOf(Objects.requireNonNull(media, "media must not be null"));
    }
}
