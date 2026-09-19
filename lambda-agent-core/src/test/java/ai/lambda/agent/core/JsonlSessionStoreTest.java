package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonlSessionStoreTest {

    @TempDir
    Path storageDir;

    @Test
    void roundTripsMessagesAndMetadata() {
        JsonlSessionStore store = new JsonlSessionStore(storageDir);
        AgentSession session = new AgentSession("session-1");
        session.getMessages().add(new Message(Role.USER, "hello", null));
        session.getMetadata().put("count", 2);

        store.save(session);
        AgentSession loaded = store.loadOrCreate("session-1");

        assertEquals("hello", loaded.getMessages().get(0).getContent());
        assertEquals(2, loaded.getMetadata().get("count"));
    }

    @Test
    void rejectsPathTraversalSessionIds() {
        JsonlSessionStore store = new JsonlSessionStore(storageDir);

        assertThrows(IllegalArgumentException.class, () -> store.loadOrCreate("../outside"));
        assertThrows(IllegalArgumentException.class, () -> store.loadOrCreate("a/b"));
    }
}
