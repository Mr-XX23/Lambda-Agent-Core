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
    Path dir;

    @Test
    void unknownSessionStartsEmpty() {
        AgentSession session = new JsonlSessionStore(dir).loadOrCreate("new");

        assertEquals("new", session.getId());
        assertTrue(session.getMessages().isEmpty());
        assertTrue(session.getMetadata().isEmpty());
    }

    @Test
    void savedSessionSurvivesReload() {
        AgentSession session = new AgentSession("todo");
        session.getMessages().add(new Message(Role.SYSTEM, "sys", null));
        session.getMessages().add(new Message(Role.USER, "add milk", null));
        session.getMessages().add(new Message(Role.ASSISTANT, "", null, null,
                List.of(new ToolCall("c1", "add_todo", "{\"task\":\"milk\"}", "sig-1"))));
        session.getMessages().add(new Message(Role.TOOL, "Added", "c1", "add_todo", null));
        session.getMetadata().put("todos", new ArrayList<>(List.of("milk", "eggs")));

        new JsonlSessionStore(dir).save(session);
        AgentSession loaded = new JsonlSessionStore(dir).loadOrCreate("todo");

        assertEquals(4, loaded.getMessages().size());
        ToolCall call = loaded.getMessages().get(2).getToolCalls().get(0);
        assertEquals("add_todo", call.getName());
        assertEquals("sig-1", call.getSignature());
        assertEquals("add_todo", loaded.getMessages().get(3).getToolCallName());
        assertEquals(List.of("milk", "eggs"), loaded.getMetadata().get("todos"));
    }
}
