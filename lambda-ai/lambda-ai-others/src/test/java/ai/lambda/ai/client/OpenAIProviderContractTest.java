package ai.lambda.ai.client;

import ai.lambda.ai.core.*;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAIProviderContractTest {
    @Test
    void normalizesUsageAndFinishReasons() {
        ModelUsage usage = OpenAICompatibleModelClient.parseUsage(new JSONObject(
                "{\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":6,\"total_tokens\":10}}"));
        assertEquals(new ModelUsage(4, 6, 10), usage);
        assertEquals(FinishReason.TOOL_CALLS, OpenAICompatibleModelClient.toFinishReason("tool_calls"));
        assertEquals(FinishReason.CONTENT_FILTER, OpenAICompatibleModelClient.toFinishReason("content_filter"));
        assertEquals(FinishReason.UNKNOWN, OpenAICompatibleModelClient.toFinishReason("unexpected"));
    }

    @Test
    void missingStreamingUsageIsEmpty() {
        assertEquals(ModelUsage.empty(), OpenAICompatibleModelClient.parseUsage(new JSONObject()));
    }
}
