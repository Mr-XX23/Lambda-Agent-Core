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

    public AgentConfig(String systemPrompt, ModelClient modelClient) {
        this(systemPrompt, modelClient, List.of(), 8);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.maxIterations = maxIterations <= 0 ? 8 : maxIterations;
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

    public List<AgentTool> tools() {
        return Collections.unmodifiableList(tools);
    }
}
