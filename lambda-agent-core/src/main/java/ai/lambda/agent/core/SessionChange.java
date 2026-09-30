package ai.lambda.agent.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One save for a {@link SessionDatabase} to apply. Usually only new messages are sent: the agent
 * adds messages and never changes earlier ones, so {@code keptMessages} is the number already
 * stored. When earlier messages were changed or removed, fewer are kept and the rest are rewritten.
 *
 * @param sessionId       the session
 * @param expectedVersion the version the session must have now; 0 means it must not exist yet
 * @param keptMessages    how many stored messages stay; later ones are dropped
 * @param newMessages     messages (JSON strings) to append after the kept ones
 * @param metadataJson    the new metadata, one JSON object string
 * @param addedMedia      media to store, bytes by name
 * @param removedMedia    names of media no message refers to any more
 */
public record SessionChange(String sessionId, long expectedVersion, int keptMessages, List<String> newMessages,
                            String metadataJson, Map<String, byte[]> addedMedia, Set<String> removedMedia) {

    public SessionChange {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (expectedVersion < 0 || keptMessages < 0) throw new IllegalArgumentException("negative version or count");
        newMessages = List.copyOf(newMessages);
        Objects.requireNonNull(metadataJson, "metadataJson must not be null");
        addedMedia = Map.copyOf(addedMedia);
        removedMedia = Set.copyOf(removedMedia);
    }

    /** True when the session is saved for the first time. */
    public boolean isNew() {
        return expectedVersion == 0;
    }

    /** The version to store. */
    public long newVersion() {
        return expectedVersion + 1;
    }

    /** The exception to throw when the stored version is not {@link #expectedVersion()}. */
    public OptimisticLockException conflict() {
        return new OptimisticLockException(isNew()
                ? "Session '" + sessionId + "' already exists; load it with loadOrCreate before saving"
                : "Session '" + sessionId + "' was saved elsewhere after it was loaded (expected version "
                        + expectedVersion + "); load it again and retry");
    }
}
