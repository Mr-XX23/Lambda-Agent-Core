package ai.lambda.ai.core;

import java.util.function.Consumer;
import java.util.List;

public interface ModelClient {
    ChatResponse chat(List<Message> messages, List<ToolSchema> toolSchemas);

    ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta);

    /**
     * What this client's model accepts. The default is text only, with tool calling; clients
     * that support images, audio, video or documents override it.
     */
    default ModelCapabilities capabilities() {
        return ModelCapabilities.textOnly();
    }

    /**
     * Counts the tokens these messages use. The default is a rough estimate of about four
     * characters per token; clients whose provider has a token-counting API override it.
     */
    default int countTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message m : messages) {
            totalChars += m.getContent().length();
        }
        return totalChars / 4;
    }
}
