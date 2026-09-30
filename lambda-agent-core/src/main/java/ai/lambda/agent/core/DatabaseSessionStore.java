package ai.lambda.agent.core;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps sessions in your own database through a {@link SessionDatabase}, so several application
 * instances can share them. No database driver is bundled: you bring the database and its client.
 *
 * <pre>
 * // SQL (Postgres, MySQL, SQL Server, Oracle, H2...): any JDBC DataSource
 * JdbcSessionDatabase database = new JdbcSessionDatabase(dataSource);
 * database.initializeSchema();
 * Agent agent = new Agent(config, new DatabaseSessionStore(database));
 *
 * // NoSQL: implement SessionDatabase with your client (see docs/DatabaseSessions.md)
 * Agent agent = new Agent(config, new DatabaseSessionStore(new MongoSessionDatabase(mongo)));
 * </pre>
 *
 * <p>Saves write only what changed: normally the messages added since the last save, plus the
 * metadata. Media is stored once per session under its SHA-256, not inline in the messages.
 * If two instances load the same session and both save, the second save throws
 * {@link OptimisticLockException} instead of silently losing the first one's messages.
 */
public final class DatabaseSessionStore implements SessionStore {

    /** What a session object looked like when it was last loaded or saved. */
    private record Snapshot(long version, List<Message> messages, Set<String> mediaNames, String metadataJson) {
        static final Snapshot NOT_STORED = new Snapshot(0, List.of(), Set.of(), null);
    }

    private final SessionDatabase database;
    private final Map<AgentSession, Snapshot> snapshots = Collections.synchronizedMap(new WeakHashMap<>());

    public DatabaseSessionStore(SessionDatabase database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public AgentSession loadOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Invalid session id: " + sessionId);
        AgentSession session = new AgentSession(sessionId);
        Optional<StoredSession> found = database.load(sessionId);
        if (found.isEmpty()) {
            snapshots.put(session, Snapshot.NOT_STORED);
            return session;
        }
        StoredSession stored = found.get();
        try {
            SessionJson.decodeMetadata(stored.metadataJson(), session.getMetadata());
            for (String json : stored.messages()) {
                session.getMessages().add(SessionJson.decode(new JSONObject(json), name -> {
                    byte[] data = stored.media().get(name);
                    if (data == null) throw new IOException("Media missing for session " + sessionId + ": " + name);
                    return data;
                }));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load session " + sessionId, e);
        }
        snapshots.put(session, new Snapshot(stored.version(), List.copyOf(session.getMessages()),
                Set.copyOf(stored.media().keySet()), SessionJson.encodeMetadata(session.getMetadata())));
        return session;
    }

    @Override
    public void save(AgentSession session) {
        Objects.requireNonNull(session, "session must not be null");
        List<Message> messages = List.copyOf(session.getMessages());
        String metadataJson = SessionJson.encodeMetadata(session.getMetadata());
        // A session this store did not load is saved as new; if it exists, that is a conflict.
        Snapshot before = snapshots.getOrDefault(session, Snapshot.NOT_STORED);

        // Messages are immutable, so an unchanged message is the same object as before.
        int kept = 0;
        int common = Math.min(messages.size(), before.messages().size());
        while (kept < common && messages.get(kept) == before.messages().get(kept)) kept++;
        boolean appendOnly = kept == before.messages().size();
        if (appendOnly && kept == messages.size() && metadataJson.equals(before.metadataJson())) return;

        Map<String, Media> referenced = new LinkedHashMap<>();
        List<String> newMessages = new ArrayList<>();
        for (Message message : messages.subList(kept, messages.size())) {
            newMessages.add(SessionJson.encode(message, referenced).toString());
        }
        Set<String> mediaNames = new HashSet<>();
        if (appendOnly) {
            mediaNames.addAll(before.mediaNames()); // the stored messages, and so their media, stay
        } else {
            for (Message message : messages.subList(0, kept)) {
                for (Media media : message.getMedia()) if (media.hasData()) mediaNames.add(SessionJson.mediaName(media));
            }
        }
        Map<String, byte[]> addedMedia = new HashMap<>();
        for (Map.Entry<String, Media> entry : referenced.entrySet()) {
            mediaNames.add(entry.getKey());
            if (!before.mediaNames().contains(entry.getKey())) addedMedia.put(entry.getKey(), entry.getValue().data());
        }
        Set<String> removedMedia = new HashSet<>(before.mediaNames());
        removedMedia.removeAll(mediaNames);

        SessionChange change = new SessionChange(session.getId(), before.version(), kept, newMessages,
                metadataJson, addedMedia, removedMedia);
        database.write(change);
        snapshots.put(session, new Snapshot(change.newVersion(), messages, Set.copyOf(mediaNames), metadataJson));
    }
}
