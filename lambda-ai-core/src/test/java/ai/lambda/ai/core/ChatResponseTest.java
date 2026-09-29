package ai.lambda.ai.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatResponseTest {
    @Test
    void preservesNormalizedFinishReasonUsageAndToolCalls() {
        ToolCall call = new ToolCall("id", "tool", "{}");
        ChatResponse response = new ChatResponse(
                new Message(Role.ASSISTANT, "text", null),
                List.of(call),
                FinishReason.TOOL_CALLS,
                new ModelUsage(2, 3, 5));

        assertEquals(FinishReason.TOOL_CALLS, response.getFinishReason());
        assertEquals(new ModelUsage(2, 3, 5), response.getUsage());
        assertEquals(List.of(call), response.getToolCalls());
    }

    @Test
    void rejectsNegativeUsage() {
        assertThrows(IllegalArgumentException.class, () -> new ModelUsage(-1, 0, 0));
    }
}
