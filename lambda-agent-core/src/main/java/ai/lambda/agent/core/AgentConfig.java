package ai.lambda.agent.core;
import java.util.Collections;
import java.util.List;

import ai.lambda.ai.core.ModelClient;
import java.util.Objects;
import java.time.Duration;

public final class AgentConfig {

    private final String systemPrompt;
    private final ModelClient modelClient;
    private final List<AgentTool> tools;
    private final int maxIterations;
    private final ToolErrorStrategy toolErrorStrategy;
    private final Duration runTimeout;
    private final int maxToolArgumentLength;
    private final RetryPolicy modelRetryPolicy;
    private final ToolApprovalHandler toolApprovalHandler;

    public AgentConfig(String systemPrompt, ModelClient modelClient) {
        this(systemPrompt, modelClient, List.of(), 8, ToolErrorStrategy.SEND_TO_MODEL);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations) {
        this(systemPrompt, modelClient, tools, maxIterations, ToolErrorStrategy.SEND_TO_MODEL);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations, ToolErrorStrategy toolErrorStrategy) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, Duration.ofMinutes(5), 64 * 1024);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations,
                       ToolErrorStrategy toolErrorStrategy, Duration runTimeout, int maxToolArgumentLength) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy,
                runTimeout, maxToolArgumentLength, RetryPolicy.none());
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations,
                       ToolErrorStrategy toolErrorStrategy, Duration runTimeout, int maxToolArgumentLength,
                       RetryPolicy modelRetryPolicy) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, (sessionId, call) -> false);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations,
                       ToolErrorStrategy toolErrorStrategy, Duration runTimeout, int maxToolArgumentLength,
                       RetryPolicy modelRetryPolicy, ToolApprovalHandler toolApprovalHandler) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.maxIterations = maxIterations <= 0 ? 8 : maxIterations;
        this.toolErrorStrategy = toolErrorStrategy == null ? ToolErrorStrategy.SEND_TO_MODEL : toolErrorStrategy;
        this.runTimeout = Objects.requireNonNull(runTimeout, "runTimeout must not be null");
        if (runTimeout.isNegative() || runTimeout.isZero()) {
            throw new IllegalArgumentException("runTimeout must be positive");
        }
        if (maxToolArgumentLength <= 0) {
            throw new IllegalArgumentException("maxToolArgumentLength must be positive");
        }
        this.maxToolArgumentLength = maxToolArgumentLength;
        this.modelRetryPolicy = Objects.requireNonNull(modelRetryPolicy, "modelRetryPolicy must not be null");
        this.toolApprovalHandler = Objects.requireNonNull(toolApprovalHandler, "toolApprovalHandler must not be null");
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

    public Duration getRunTimeout() {
        return runTimeout;
    }

    public int getMaxToolArgumentLength() {
        return maxToolArgumentLength;
    }

    public RetryPolicy getModelRetryPolicy() {
        return modelRetryPolicy;
    }

    public ToolApprovalHandler getToolApprovalHandler() {
        return toolApprovalHandler;
    }
}
