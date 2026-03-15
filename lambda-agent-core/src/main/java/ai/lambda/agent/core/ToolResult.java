package ai.lambda.agent.core;

import java.util.Collections;
import java.util.Map;

public final class ToolResult {

    private final String content;
    private final Map<String, Object> details;

    public ToolResult(String content, Map<String, Object> details) {
        this.content = content == null ? "" : content;
        this.details = details == null ? Collections.emptyMap() : Map.copyOf(details);
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static ToolResult of(String content) {
        return new ToolResult(content, Map.of());
    }

}
