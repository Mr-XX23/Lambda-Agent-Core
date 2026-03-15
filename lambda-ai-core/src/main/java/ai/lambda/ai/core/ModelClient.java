package ai.lambda.ai.core;

import java.util.List;

public interface ModelClient {
    ChatResponse chat(List<Message> messages, List<ToolSchema> toolSchemas);
}
