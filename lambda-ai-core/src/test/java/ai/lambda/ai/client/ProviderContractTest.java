package ai.lambda.ai.client;

import ai.lambda.ai.core.FinishReason;
import ai.lambda.ai.core.ModelUsage;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderContractTest {
    @Test
    void geminiUsageMetadataMapsToNormalizedUsage() {
        ModelUsage usage = GoogleModelClient.parseUsage(new JSONObject("""
                {"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":7,"totalTokenCount":18}}
                """));

        assertEquals(new ModelUsage(11, 7, 18), usage);
    }

    @Test
    void geminiFinishReasonsMapToSharedContract() {
        assertEquals(FinishReason.STOP, GoogleModelClient.toFinishReason("STOP", false));
        assertEquals(FinishReason.LENGTH, GoogleModelClient.toFinishReason("MAX_TOKENS", false));
        assertEquals(FinishReason.CONTENT_FILTER, GoogleModelClient.toFinishReason("SAFETY", false));
        assertEquals(FinishReason.TOOL_CALLS, GoogleModelClient.toFinishReason("STOP", true));
    }

    @Test
    void geminiMissingMetadataIsSafe() {
        assertEquals(ModelUsage.empty(), GoogleModelClient.parseUsage(new JSONObject()));
        assertEquals(FinishReason.UNKNOWN, GoogleModelClient.toFinishReason("UNSPECIFIED", false));
    }
}
