package ai.lambda.agent.core;

import ai.lambda.ai.core.Media;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class JdbcSessionDatabaseTest {

    private static final byte[] PHOTO = new byte[300_000];

    static {
        for (int i = 0; i < PHOTO.length; i++) PHOTO[i] = (byte) (i * 13);
    }

    /** A fresh in-memory H2 database, optionally imitating another database ("PostgreSQL", "MySQL"). */
    private static JdbcDataSource h2(String mode) {
        JdbcDataSource dataSource = new JdbcDataSource();
        String options = switch (mode) {
            case "PostgreSQL" -> ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
            case "MySQL" -> ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
            default -> "";
        };
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1" + options);
        return dataSource;
    }

    private static JdbcSessionDatabase database(JdbcDataSource dataSource) {
        JdbcSessionDatabase database = new JdbcSessionDatabase(dataSource);
        database.initializeSchema();
        return database;
    }

    private static long count(JdbcDataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> contents(AgentSession session) {
        return session.getMessages().stream().map(Message::getContent).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"H2", "PostgreSQL", "MySQL"})
    void storesSessionsWithMediaAndLargeMessages(String mode) throws SQLException {
        JdbcDataSource dataSource = h2(mode);
        JdbcSessionDatabase database = database(dataSource);
        database.initializeSchema(); // a second call finds the tables and leaves them

        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("user-42/chat");
        String bigToolResult = "row,".repeat(50_000); // 200 KB: more than a 64 KB TEXT column holds
        session.getMessages().add(new Message(Role.SYSTEM, "sys", null));
        session.getMessages().add(Message.user("what is this? ünïcödé ✓", Media.of(PHOTO, "image/jpeg").withName("p.jpg")));
        session.getMessages().add(new Message(Role.TOOL, bigToolResult, "c1", "query", null));
        session.getMetadata().put("todos", new ArrayList<>(List.of("milk")));
        store.save(session);

        AgentSession loaded = new DatabaseSessionStore(database).loadOrCreate("user-42/chat");
        assertEquals(List.of("sys", "what is this? ünïcödé ✓", bigToolResult), contents(loaded));
        Media photo = loaded.getMessages().get(1).getMedia().get(0);
        assertArrayEquals(PHOTO, photo.data());
        assertEquals("p.jpg", photo.name());
        assertEquals(List.of("milk"), loaded.getMetadata().get("todos"));
        assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM lambda_session_media"));
    }

    @Test
    void savesAppendRowsAndRewritesOnlyWhatChanged() throws SQLException {
        JdbcDataSource dataSource = h2("H2");
        DatabaseSessionStore store = new DatabaseSessionStore(database(dataSource));
        AgentSession session = store.loadOrCreate("s");
        session.getMessages().add(new Message(Role.USER, "one", null));
        session.getMessages().add(Message.user("two", Media.of(PHOTO, "image/png")));
        store.save(session);
        session.getMessages().add(new Message(Role.USER, "three", null));
        store.save(session);
        assertEquals(3, count(dataSource, "SELECT COUNT(*) FROM lambda_session_messages"));
        assertEquals(2, count(dataSource, "SELECT version FROM lambda_sessions WHERE session_id = 's'"));

        session.getMessages().remove(1); // drops the message with the photo
        store.save(session);

        assertEquals(2, count(dataSource, "SELECT COUNT(*) FROM lambda_session_messages"));
        assertEquals(1, count(dataSource, "SELECT MAX(message_index) FROM lambda_session_messages"));
        assertEquals(0, count(dataSource, "SELECT COUNT(*) FROM lambda_session_media"));
        assertEquals(List.of("one", "three"), contents(new DatabaseSessionStore(database(dataSource)).loadOrCreate("s")));
    }

    @Test
    void twoServersSavingOneSessionConflictInsteadOfLosingMessages() {
        JdbcDataSource dataSource = h2("H2");
        DatabaseSessionStore server1 = new DatabaseSessionStore(database(dataSource));
        DatabaseSessionStore server2 = new DatabaseSessionStore(new JdbcSessionDatabase(dataSource));
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
        JdbcDataSource dataSource = h2("H2");
        JdbcSessionDatabase database = database(dataSource);
        int servers = 8;
        CountDownLatch ready = new CountDownLatch(servers);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(servers)) {
            for (int i = 0; i < servers; i++) {
                String text = "from " + i;
                results.add(pool.submit(() -> {
                    DatabaseSessionStore store = new DatabaseSessionStore(database);
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
        assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM lambda_session_messages"));
    }

    @Test
    void customTablePrefixAndSchema() throws SQLException {
        JdbcDataSource dataSource = h2("H2");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA app");
        }
        JdbcSessionDatabase database = new JdbcSessionDatabase(dataSource, "app.agent_");
        database.initializeSchema();
        database.initializeSchema();
        DatabaseSessionStore store = new DatabaseSessionStore(database);
        AgentSession session = store.loadOrCreate("s");
        session.getMessages().add(new Message(Role.USER, "hi", null));
        store.save(session);

        assertEquals(1, count(dataSource, "SELECT COUNT(*) FROM app.agent_session_messages"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcSessionDatabase(dataSource, "x; DROP TABLE y"));
    }

    @Test
    void unknownSessionLoadsEmptyAndLongIdsAreRejected() {
        JdbcSessionDatabase database = database(h2("H2"));
        assertTrue(database.load("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> database.load("x".repeat(256)));
    }
}
