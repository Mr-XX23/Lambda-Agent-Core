package ai.lambda.agent.core;
import java.util.Collections;
import java.util.List;

import ai.lambda.ai.core.ModelClient;
import java.util.Objects;

public final class AgentConfig {

    private final String systemPrompt;
    private final ModelClient modelClient;
    private final List<AgentTool> tools;
    private final int maxIterations;
    private final ToolErrorStrategy toolErrorStrategy;

    public AgentConfig(String systemPrompt, ModelClient modelClient) {
        this(systemPrompt, modelClient, List.of(), 8, ToolErrorStrategy.SEND_TO_MODEL);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations) {
        this(systemPrompt, modelClient, tools, maxIterations, ToolErrorStrategy.SEND_TO_MODEL);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations, ToolErrorStrategy toolErrorStrategy) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.maxIterations = maxIterations <= 0 ? 8 : maxIterations;
        this.toolErrorStrategy = toolErrorStrategy == null ? ToolErrorStrategy.SEND_TO_MODEL : toolErrorStrategy;
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

    public List<AgentTool> tools() {
        return Collections.unmodifiableList(tools);
    }
}
