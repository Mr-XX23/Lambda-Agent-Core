package ai.lambda.agent.core;

import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    @Test
    void unknownSessionStartsEmpty() {
        AgentSession session = new JsonlSessionStore(storageDir).loadOrCreate("new");

        assertEquals("new", session.getId());
        assertTrue(session.getMessages().isEmpty());
        assertTrue(session.getMetadata().isEmpty());
    }

    @Test
    void toolCallsSignaturesAndListMetadataSurviveReload() {
        AgentSession session = new AgentSession("todo");
        session.getMessages().add(new Message(Role.SYSTEM, "sys", null));
        session.getMessages().add(new Message(Role.USER, "add milk", null));
        session.getMessages().add(new Message(Role.ASSISTANT, "", null, null,
                List.of(new ToolCall("c1", "add_todo", "{\"task\":\"milk\"}", "sig-1"))));
        session.getMessages().add(new Message(Role.TOOL, "Added", "c1", "add_todo", null));
        session.getMetadata().put("todos", new ArrayList<>(List.of("milk", "eggs")));

        new JsonlSessionStore(storageDir).save(session);
        AgentSession loaded = new JsonlSessionStore(storageDir).loadOrCreate("todo");

        assertEquals(4, loaded.getMessages().size());
        ToolCall call = loaded.getMessages().get(2).getToolCalls().get(0);
        assertEquals("add_todo", call.getName());
        assertEquals("sig-1", call.getSignature());
        assertEquals("add_todo", loaded.getMessages().get(3).getToolCallName());
        assertEquals(List.of("milk", "eggs"), loaded.getMetadata().get("todos"));
    }
}
