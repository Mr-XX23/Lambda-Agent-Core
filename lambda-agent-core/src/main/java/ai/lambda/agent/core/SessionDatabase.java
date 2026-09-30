package ai.lambda.agent.core;

import java.util.Optional;

/**
 * The bridge between {@link DatabaseSessionStore} and your own database. Implement these two
 * methods with the client you already use (a JDBC {@code DataSource}, MongoDB, Redis, DynamoDB,
 * Cassandra...) and the store handles the rest: turning messages, metadata and media into
 * strings and bytes, writing only what changed, and detecting conflicting saves.
 * {@link JdbcSessionDatabase} is the built-in implementation for SQL databases.
 *
 * <p>What to keep for each session id:
 * <ul>
 *   <li>a version number,</li>
 *   <li>the metadata, one JSON string,</li>
 *   <li>the messages, an ordered list of JSON strings,</li>
 *   <li>media, bytes stored under a name (the name is unique within the session).</li>
 * </ul>
 */
public interface SessionDatabase {

    /** The stored session, or empty if there is none with this id. */
    Optional<StoredSession> load(String sessionId);

    /**
     * Applies one save, preferably atomically:
     * <ol>
     *   <li>If the stored version is not {@link SessionChange#expectedVersion()} (0 means the
     *       session must not exist yet), throw {@link SessionChange#conflict()} and change nothing.</li>
     *   <li>Keep the first {@link SessionChange#keptMessages()} messages, drop the rest, then
     *       append {@link SessionChange#newMessages()}.</li>
     *   <li>Set the version to {@link SessionChange#newVersion()} and the metadata to
     *       {@link SessionChange#metadataJson()}.</li>
     *   <li>Store {@link SessionChange#addedMedia()} and delete {@link SessionChange#removedMedia()}.</li>
     * </ol>
     * If the database cannot make the whole change atomic, store added media before the messages
     * and delete removed media last, so messages never refer to missing media.
     */
    void write(SessionChange change);
}
