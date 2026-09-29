package ai.lambda.ai.core;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void jsonRoundTripKeepsToolCallsAndSignatures() {
        Message original = new Message(Role.ASSISTANT, "thinking", null, null, List.of(
                new ToolCall("c1", "add_todo", "{\"task\":\"milk\"}", "sig-1"),
                new ToolCall("c2", "list_todos", "{}")
        ));

        Message copy = Message.fromJson(new JSONObject(original.toJson().toString()));

        assertEquals(Role.ASSISTANT, copy.getRole());
        assertEquals("thinking", copy.getContent());
        assertEquals(2, copy.getToolCalls().size());
        assertEquals("c1", copy.getToolCalls().get(0).getId());
        assertEquals("add_todo", copy.getToolCalls().get(0).getName());
        assertEquals("{\"task\":\"milk\"}", copy.getToolCalls().get(0).getArgumentsJson());
        assertEquals("sig-1", copy.getToolCalls().get(0).getSignature());
        assertNull(copy.getToolCalls().get(1).getSignature());
    }

    @Test
    void jsonRoundTripKeepsToolResultFields() {
        Message original = new Message(Role.TOOL, "Added", "c1", "add_todo", null);

        Message copy = Message.fromJson(original.toJson());

        assertEquals(Role.TOOL, copy.getRole());
        assertEquals("c1", copy.getToolCallId());
        assertEquals("add_todo", copy.getToolCallName());
    }

    @Test
    void readsToolCallsSavedBeforeSignaturesExisted() {
        JSONObject old = new JSONObject("""
                {"role":"ASSISTANT","content":"","toolCalls":[{"id":"c1","name":"echo","argumentsJson":"{}"}]}
                """);

        Message message = Message.fromJson(old);

        assertEquals("echo", message.getToolCalls().get(0).getName());
        assertNull(message.getToolCalls().get(0).getSignature());
    }
}
