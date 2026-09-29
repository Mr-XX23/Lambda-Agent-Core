package ai.lambda.agent.core;
import java.util.Collections;
import java.util.List;

import ai.lambda.ai.core.ModelClient;
import java.util.Objects;

public final class AgentConfig {

    /** Roughly 12k tokens: large enough for most file reads, small enough not to flood the context. */
    public static final int DEFAULT_MAX_TOOL_RESULT_CHARS = 50_000;

    private final String systemPrompt;
    private final ModelClient modelClient;
    private final List<AgentTool> tools;
    private final int maxIterations;
    private final ToolErrorStrategy toolErrorStrategy;
    private final ContextStrategy contextStrategy;
    private final int maxToolResultChars;

    public AgentConfig(String systemPrompt, ModelClient modelClient) {
        this(systemPrompt, modelClient, List.of(), 8, ToolErrorStrategy.SEND_TO_MODEL, new NoOpStrategy());
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations) {
        this(systemPrompt, modelClient, tools, maxIterations, ToolErrorStrategy.SEND_TO_MODEL, new NoOpStrategy());
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations, ToolErrorStrategy toolErrorStrategy) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, new NoOpStrategy());
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations, ToolErrorStrategy toolErrorStrategy, ContextStrategy contextStrategy) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, contextStrategy, DEFAULT_MAX_TOOL_RESULT_CHARS);
    }

    private AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations, ToolErrorStrategy toolErrorStrategy, ContextStrategy contextStrategy, int maxToolResultChars) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.maxIterations = maxIterations <= 0 ? 8 : maxIterations;
        this.toolErrorStrategy = toolErrorStrategy == null ? ToolErrorStrategy.SEND_TO_MODEL : toolErrorStrategy;
        this.contextStrategy = contextStrategy == null ? new NoOpStrategy() : contextStrategy;
        if (maxToolResultChars < 1) throw new IllegalArgumentException("maxToolResultChars must be at least 1");
        this.maxToolResultChars = maxToolResultChars;
    }

    /**
     * Returns a copy where tool results longer than {@code maxChars} are shortened (keeping
     * the start and end) before they are added to the history. Listeners still receive the
     * full result. Use {@link Integer#MAX_VALUE} to turn this off.
     */
    public AgentConfig withMaxToolResultChars(int maxChars) {
        return new AgentConfig(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, contextStrategy, maxChars);
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public ModelClient getModelClient() {
        return modelClient;
    }

    public List<AgentTool> getTools() {
        return tools;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public ToolErrorStrategy getToolErrorStrategy() {
        return toolErrorStrategy;
    }

    public ContextStrategy getContextStrategy() {
        return contextStrategy;
    }

    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    public List<AgentTool> tools() {
        return Collections.unmodifiableList(tools);
    }
}
