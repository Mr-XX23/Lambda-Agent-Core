package ai.lambda.examples.databasesessions;

import ai.lambda.agent.core.AgentSession;
import ai.lambda.agent.core.DatabaseSessionStore;
import ai.lambda.agent.core.OptimisticLockException;
import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import com.mongodb.MongoClientSettings;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a MongoDB at MONGODB_URI (default localhost), in a throwaway database; skipped if none is reachable. */
class MongoSessionDatabaseTest {

    private static final byte[] PHOTO = new byte[200_000];

    static {
        for (int i = 0; i < PHOTO.length; i++) PHOTO[i] = (byte) (i * 11);
    }

    private MongoClient client;
    private MongoDatabase database;

    @BeforeEach
    void connect() {
        String uri = System.getenv().getOrDefault("MONGODB_URI", "mongodb://localhost:27017");
        client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(1, TimeUnit.SECONDS))
                .build());
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (RuntimeException unreachable) {
            client.close();
            assumeTrue(false, "No MongoDB at " + uri);
        }
        database = client.getDatabase("lambda_test_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void dropTestDatabase() {
        if (database != null) database.drop();
        if (client != null) client.close();
    }

    private static List<String> contents(AgentSession session) {
        return session.getMessages().stream().map(Message::getContent).toList();
    }

    @Test
    void storesAppendsAndRewritesSessions() {
        DatabaseSessionStore store = new DatabaseSessionStore(new MongoSessionDatabase(database));
        AgentSession session = store.loadOrCreate("chat");
        session.getMessages().add(new Message(Role.SYSTEM, "sys", null));
        session.getMessages().add(Message.user("look", Media.of(PHOTO, "image/jpeg").withName("p.jpg")));
        session.getMetadata().put("todos", new ArrayList<>(List.of("milk")));
        store.save(session);
        session.getMessages().add(new Message(Role.ASSISTANT, "a photo", null));
        store.save(session);

        AgentSession loaded = new DatabaseSessionStore(new MongoSessionDatabase(database)).loadOrCreate("chat");
        assertEquals(List.of("sys", "look", "a photo"), contents(loaded));
        assertArrayEquals(PHOTO, loaded.getMessages().get(1).getMedia().get(0).data());
        assertEquals("p.jpg", loaded.getMessages().get(1).getMedia().get(0).name());
        assertEquals(List.of("milk"), loaded.getMetadata().get("todos"));
        assertEquals(1, database.getCollection("lambda_session_media").countDocuments());

        session.getMessages().remove(1); // rewrite from the second message; the photo is no longer used
        store.save(session);
        assertEquals(List.of("sys", "a photo"), contents(store.loadOrCreate("chat")));
        assertEquals(0, database.getCollection("lambda_session_media").countDocuments());
        assertEquals(3L, database.getCollection("lambda_sessions").find().first().getLong("version"));
    }

    @Test
    void secondServerGetsAConflict() {
        DatabaseSessionStore server1 = new DatabaseSessionStore(new MongoSessionDatabase(database));
        DatabaseSessionStore server2 = new DatabaseSessionStore(new MongoSessionDatabase(database));
        AgentSession start = server1.loadOrCreate("shared");
        start.getMessages().add(new Message(Role.USER, "start", null));
        server1.save(start);

        AgentSession on1 = server1.loadOrCreate("shared");
        AgentSession on2 = server2.loadOrCreate("shared");
        on1.getMessages().add(new Message(Role.USER, "from 1", null));
        on2.getMessages().add(new Message(Role.USER, "from 2", null));
        server1.save(on1);

        assertThrows(OptimisticLockException.class, () -> server2.save(on2));
        assertEquals(List.of("start", "from 1"), contents(server2.loadOrCreate("shared")));
    }

    @Test
    void concurrentFirstSavesCreateTheSessionOnce() throws Exception {
        MongoSessionDatabase bridge = new MongoSessionDatabase(database);
        int servers = 6;
        CountDownLatch ready = new CountDownLatch(servers);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(servers)) {
            for (int i = 0; i < servers; i++) {
                String text = "from " + i;
                results.add(pool.submit(() -> {
                    DatabaseSessionStore store = new DatabaseSessionStore(bridge);
                    AgentSession session = store.loadOrCreate("new-chat");
                    session.getMessages().add(new Message(Role.USER, text, null));
                    ready.countDown();
                    ready.await();
                    try {
                        store.save(session);
                        return true;
                    } catch (OptimisticLockException conflict) {
                        return false;
                    }
                }));
            }
        }
        int saved = 0;
        for (Future<Boolean> result : results) if (result.get()) saved++;
        assertEquals(1, saved);
    }
}
