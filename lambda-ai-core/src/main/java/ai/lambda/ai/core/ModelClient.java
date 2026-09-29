package ai.lambda.ai.core;

import java.util.function.Consumer;
import java.util.List;

public interface ModelClient {
    ChatResponse chat(List<Message> messages, List<ToolSchema> toolSchemas);

    ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta);

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
