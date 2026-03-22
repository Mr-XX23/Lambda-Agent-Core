package ai.lambda.ai.core;

import java.util.function.Consumer;
import java.util.List;

public interface ModelClient {
    ChatResponse chat(List<Message> messages, List<ToolSchema> toolSchemas);

    ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta);
}
