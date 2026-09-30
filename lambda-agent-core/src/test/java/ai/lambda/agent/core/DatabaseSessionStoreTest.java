package ai.lambda.agent.core;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ProviderState;
import ai.lambda.ai.core.Role;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSessionStoreTest {

    /** The smallest possible SessionDatabase: maps in memory. It records every change it is given. */
    static final class MapSessionDatabase implements SessionDatabase {
        final Map<String, StoredSession> sessions = new HashMap<>();
        final List<SessionChange> changes = new ArrayList<>();

        @Override
        public synchronized Optional<StoredSession> load(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public synchronized void write(SessionChange change) {
            StoredSession stored = sessions.get(change.sessionId());
            long version = stored == null ? 0 : stored.version();
            if (version != change.expectedVersion()) throw change.conflict();
            List<String> messages = new ArrayList<>(stored == null ? List.of()
                    : stored.messages().subList(0, change.keptMessages()));
            messages.addAll(change.newMessages());
            Map<String, byte[]> media = new HashMap<>(stored == null ? Map.of() : stored.media());
            media.putAll(change.addedMedia());
            media.keySet().removeAll(change.removedMedia());
            sessions.put(change.sessionId(), new StoredSession(change.newVersion(), messages, change.metadataJson(), media));
            changes.add(change);
        }
    }

    private static final byte[] PHOTO = new byte[40_000];
    private static final byte[] CLIP = new byte[10_000];

    static {
        for (int i = 0; i < PHOTO.length; i++) PHOTO[i] = (byte) (i * 7);
        for (int i = 0; i < CLIP.length; i++) CLIP[i] = (byte) (i * 3);
    }

    private final MapSessionDatabase database = new MapSessionDatabase();

    @Test
    void roundTripsMessagesMetadataAndMedia() {
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("s1");
        Media photo = Media.of(PHOTO, "image/jpeg").withName("receipt.jpg");
        Media link = Media.fromUrl("https://example.com/report.pdf");
        session.getMessages().add(new Message(Role.SYSTEM, "sys", null));
        session.getMessages().add(Message.user("what is this?", photo, link));
        session.getMessages().add(new Message(Role.ASSISTANT, "a receipt", null)
                .withProviderState(new ProviderState("anthropic", "[{\"type\":\"thinking\"}]")));
        session.getMetadata().put("todos", new ArrayList<>(List.of("milk", "eggs")));
        session.getMetadata().put("count", 2);
        store.save(session);

        String stored = String.join("\n", database.sessions.get("s1").messages());
        assertFalse(stored.contains("\"data\""), "media bytes are not inside the messages");

        AgentSession loaded = new DatabaseSessionStore(database).loadOrCreate("s1");
        assertEquals(3, loaded.getMessages().size());
        assertEquals(List.of(photo, link), loaded.getMessages().get(1).getMedia());
        assertEquals("anthropic", loaded.getMessages().get(2).getProviderState().provider());
        assertEquals(List.of("milk", "eggs"), loaded.getMetadata().get("todos"));
        assertEquals(2, loaded.getMetadata().get("count"));
    }

    @Test
    void laterSavesSendOnlyNewMessagesAndNewMedia() {
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("s");
        session.getMessages().add(Message.user("look", Media.of(PHOTO, "image/jpeg")));
        session.getMessages().add(new Message(Role.ASSISTANT, "a photo", null));
        store.save(session);

        session.getMessages().add(Message.user("and again", Media.of(PHOTO, "image/jpeg")));
        session.getMessages().add(Message.user("and this", Media.of(CLIP, "audio/mpeg")));
        store.save(session);

        SessionChange first = database.changes.get(0);
        assertTrue(first.isNew());
        assertEquals(2, first.newMessages().size());
        assertEquals(1, first.addedMedia().size());
        SessionChange second = database.changes.get(1);
        assertEquals(1, second.expectedVersion());
        assertEquals(2, second.keptMessages());
        assertEquals(2, second.newMessages().size());
        assertEquals(Set.of(Media.of(CLIP, "audio/mpeg").sha256() + ".mp3"), second.addedMedia().keySet(),
                "the photo is already stored");
        assertTrue(second.removedMedia().isEmpty());
        assertEquals(2, database.sessions.get("s").media().size());
    }

    @Test
    void unchangedSessionIsNotWrittenAndMetadataAloneIs() {
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("s");
        session.getMessages().add(new Message(Role.USER, "hi", null));
        store.save(session);
        store.save(session);
        assertEquals(1, database.changes.size());

        session.getMetadata().put("step", 3);
        store.save(session);
        assertEquals(2, database.changes.size());
        assertTrue(database.changes.get(1).newMessages().isEmpty());
        assertEquals(1, database.changes.get(1).keptMessages());

        DatabaseSessionStore fresh = new DatabaseSessionStore(database);
        fresh.save(fresh.loadOrCreate("s")); // loaded and saved unchanged: nothing to write
        assertEquals(2, database.changes.size());
    }

    @Test
    void changedEarlierMessagesAreRewrittenAndTheirMediaRemoved() {
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("s");
        session.getMessages().add(new Message(Role.SYSTEM, "sys", null));
        session.getMessages().add(Message.user("old", Media.of(PHOTO, "image/jpeg")));
        session.getMessages().add(Message.user("new", Media.of(CLIP, "audio/wav")));
        store.save(session);

        session.getMessages().remove(1);
        store.save(session);

        SessionChange change = database.changes.get(1);
        assertEquals(1, change.keptMessages());
        assertEquals(1, change.newMessages().size());
        assertEquals(Set.of(Media.of(PHOTO, "image/jpeg").sha256() + ".jpg"), change.removedMedia());
        assertTrue(change.addedMedia().isEmpty());
        AgentSession loaded = new DatabaseSessionStore(database).loadOrCreate("s");
        assertEquals(List.of("sys", "new"), loaded.getMessages().stream().map(Message::getContent).toList());
        assertArrayEquals(CLIP, loaded.getMessages().get(1).getMedia().get(0).data());
    }

    @Test
    void identicalMediaIsStoredOnce() {
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("s");
        session.getMessages().add(Message.user("one", Media.of(PHOTO, "image/png").withName("a.png")));
        session.getMessages().add(Message.user("two", Media.of(PHOTO, "image/png").withName("b.png")));
        store.save(session);

        assertEquals(1, database.sessions.get("s").media().size());
        AgentSession loaded = new DatabaseSessionStore(database).loadOrCreate("s");
        assertEquals("b.png", loaded.getMessages().get(1).getMedia().get(0).name());
    }

    @Test
    void secondInstanceSavingTheSameSessionGetsAConflict() {
        DatabaseSessionStore server1 = new DatabaseSessionStore(database);
        DatabaseSessionStore server2 = new DatabaseSessionStore(database);
        AgentSession created = server1.loadOrCreate("shared");
        created.getMessages().add(new Message(Role.USER, "start", null));
        server1.save(created);

        AgentSession onServer1 = server1.loadOrCreate("shared");
        AgentSession onServer2 = server2.loadOrCreate("shared");
        onServer1.getMessages().add(new Message(Role.USER, "from 1", null));
        onServer2.getMessages().add(new Message(Role.USER, "from 2", null));
        server1.save(onServer1);

        OptimisticLockException error = assertThrows(OptimisticLockException.class, () -> server2.save(onServer2));
        assertTrue(error.getMessage().contains("shared"));
        AgentSession latest = server2.loadOrCreate("shared");
        assertEquals(List.of("start", "from 1"), latest.getMessages().stream().map(Message::getContent).toList());

        latest.getMessages().add(new Message(Role.USER, "from 2", null)); // retry after reloading
        server2.save(latest);
        assertEquals(3, database.sessions.get("shared").messages().size());
    }

    @Test
    void savingASessionThatWasNotLoadedDoesNotOverwriteAStoredOne() {
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession first = store.loadOrCreate("s");
        first.getMessages().add(new Message(Role.USER, "kept", null));
        store.save(first);

        AgentSession stranger = new AgentSession("s");
        stranger.getMessages().add(new Message(Role.USER, "overwrite?", null));
        OptimisticLockException error = assertThrows(OptimisticLockException.class, () -> store.save(stranger));
        assertTrue(error.getMessage().contains("already exists"));
        assertEquals(1, database.sessions.get("s").messages().size());
    }

    @Test
    void failedSaveCanBeRetried() {
        SessionDatabase flaky = new SessionDatabase() {
            int calls;

            @Override
            public Optional<StoredSession> load(String sessionId) {
                return database.load(sessionId);
            }

            @Override
            public void write(SessionChange change) {
                if (calls++ == 1) throw new IllegalStateException("connection lost");
                database.write(change);
            }
        };
        DatabaseSessionStore store = new DatabaseSessionStore(flaky);
        AgentSession session = store.loadOrCreate("s");
        session.getMessages().add(new Message(Role.USER, "one", null));
        store.save(session);
        session.getMessages().add(new Message(Role.USER, "two", null));

        assertThrows(IllegalStateException.class, () -> store.save(session));
        store.save(session);

        assertEquals(List.of("one", "two"), new DatabaseSessionStore(database).loadOrCreate("s").getMessages()
                .stream().map(Message::getContent).toList());
    }

    @Test
    void rejectsBadOrMissingMediaReferences() {
        String outside = new Message(Role.USER, "hi", null).toJson().put("media", new JSONArray()
                .put(new JSONObject().put("mimeType", "text/plain").put("file", "../secret.txt"))).toString();
        database.sessions.put("bad", new StoredSession(1, List.of(outside), "{}", Map.of()));
        RuntimeException bad = assertThrows(RuntimeException.class, () -> new DatabaseSessionStore(database).loadOrCreate("bad"));
        assertTrue(bad.getCause().getMessage().contains("Invalid media name"), bad.getCause().getMessage());

        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("gone");
        session.getMessages().add(Message.user("look", Media.of(PHOTO, "image/jpeg")));
        store.save(session);
        StoredSession stored = database.sessions.get("gone");
        database.sessions.put("gone", new StoredSession(stored.version(), stored.messages(), stored.metadataJson(), Map.of()));
        RuntimeException missing = assertThrows(RuntimeException.class, () -> store.loadOrCreate("gone"));
        assertTrue(missing.getCause().getMessage().contains("Media missing for session gone"), missing.getCause().getMessage());
    }

    @Test
    void worksAsTheAgentsSessionStore() {
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        FakeModelClient model = new FakeModelClient().replyText("Hello!").replyText("Still here.");
        Agent agent = new Agent(new AgentConfig("You are helpful.", model), store);

        agent.run("chat", "hi");
        agent.run("chat", "are you there?");

        AgentSession loaded = new DatabaseSessionStore(database).loadOrCreate("chat");
        assertEquals(List.of("You are helpful.", "hi", "Hello!", "are you there?", "Still here."),
                loaded.getMessages().stream().map(Message::getContent).toList());
        SessionChange last = database.changes.getLast();
        assertEquals(3, last.keptMessages(), "the second run appended only its own messages");
        assertEquals(2, last.newMessages().size());
    }
}
