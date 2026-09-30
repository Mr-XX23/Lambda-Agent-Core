package ai.lambda.agent.core;
import java.util.Collections;
import java.util.List;

import ai.lambda.ai.core.ModelClient;
import java.util.Objects;
import java.time.Duration;

public final class AgentConfig {

    private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_TOOL_ARGUMENT_LENGTH = 64 * 1024;

    private final String systemPrompt;
    private final ModelClient modelClient;
    private final List<AgentTool> tools;
    private final int maxIterations;
    private final ToolErrorStrategy toolErrorStrategy;
    private final Duration runTimeout;
    private final int maxToolArgumentLength;
    private final RetryPolicy modelRetryPolicy;
    private final ToolApprovalHandler toolApprovalHandler;
    private final ToolPermissionPolicy toolPermissionPolicy;
    private final ContextStrategy contextStrategy;
    private final Subagents subagents;
    private final boolean parallelToolCalls;
    private final boolean streaming;

    public AgentConfig(String systemPrompt, ModelClient modelClient) {
        this(systemPrompt, modelClient, List.of(), 8, ToolErrorStrategy.SEND_TO_MODEL);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations) {
        this(systemPrompt, modelClient, tools, maxIterations, ToolErrorStrategy.SEND_TO_MODEL);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations, ToolErrorStrategy toolErrorStrategy) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, DEFAULT_RUN_TIMEOUT, DEFAULT_MAX_TOOL_ARGUMENT_LENGTH);
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations,
                       ToolErrorStrategy toolErrorStrategy, ContextStrategy contextStrategy) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, DEFAULT_RUN_TIMEOUT,
                DEFAULT_MAX_TOOL_ARGUMENT_LENGTH, RetryPolicy.none(), (sessionId, call) -> false,
                ToolPermissionPolicy.allowAll(), contextStrategy, null);
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
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, toolApprovalHandler, ToolPermissionPolicy.allowAll());
    }

    public AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations,
                       ToolErrorStrategy toolErrorStrategy, Duration runTimeout, int maxToolArgumentLength,
                       RetryPolicy modelRetryPolicy, ToolApprovalHandler toolApprovalHandler,
                       ToolPermissionPolicy toolPermissionPolicy) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, toolApprovalHandler, toolPermissionPolicy, new NoOpStrategy(), null);
    }

    private AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations,
                        ToolErrorStrategy toolErrorStrategy, Duration runTimeout, int maxToolArgumentLength,
                        RetryPolicy modelRetryPolicy, ToolApprovalHandler toolApprovalHandler,
                        ToolPermissionPolicy toolPermissionPolicy, ContextStrategy contextStrategy,
                        Subagents subagents) {
        this(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout, maxToolArgumentLength,
                modelRetryPolicy, toolApprovalHandler, toolPermissionPolicy, contextStrategy, subagents, false, true);
    }

    private AgentConfig(String systemPrompt, ModelClient modelClient, List<AgentTool> tools, int maxIterations,
                        ToolErrorStrategy toolErrorStrategy, Duration runTimeout, int maxToolArgumentLength,
                        RetryPolicy modelRetryPolicy, ToolApprovalHandler toolApprovalHandler,
                        ToolPermissionPolicy toolPermissionPolicy, ContextStrategy contextStrategy,
                        Subagents subagents, boolean parallelToolCalls, boolean streaming) {
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
        this.toolPermissionPolicy = Objects.requireNonNull(toolPermissionPolicy, "toolPermissionPolicy must not be null");
        this.contextStrategy = contextStrategy == null ? new NoOpStrategy() : contextStrategy;
        this.subagents = subagents;
        this.parallelToolCalls = parallelToolCalls;
        this.streaming = streaming;
    }

    /**
     * Returns a copy that trims the history sent to the model with {@code contextStrategy}
     * (for example {@link SlidingWindowStrategy} or {@link TokenLimitStrategy}). The stored
     * session always keeps the full history.
     */
    public AgentConfig withContextStrategy(ContextStrategy contextStrategy) {
        return new AgentConfig(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, toolApprovalHandler, toolPermissionPolicy, contextStrategy,
                subagents, parallelToolCalls, streaming);
    }

    /**
     * Returns a copy that can use {@code skills}: their names and descriptions are appended to
     * the system prompt, and the {@code load_skill} and {@code read_skill_file} tools are added.
     * Sessions that already exist keep the system prompt they started with.
     */
    public AgentConfig withSkills(Skills skills) {
        Objects.requireNonNull(skills, "skills must not be null");
        if (skills.isEmpty()) return this;
        List<AgentTool> allTools = new java.util.ArrayList<>(tools);
        allTools.addAll(skills.tools());
        return new AgentConfig(systemPrompt + "\n\n" + skills.promptSection(), modelClient, allTools,
                maxIterations, toolErrorStrategy, runTimeout, maxToolArgumentLength, modelRetryPolicy,
                toolApprovalHandler, toolPermissionPolicy, contextStrategy, subagents, parallelToolCalls, streaming);
    }

    /**
     * Returns a copy that can delegate work to {@code subagents} through an {@code invoke_subagent}
     * tool. Subagents inherit this config's permission policy, approval handler, retry policy,
     * context strategy, iteration limit and run timeout.
     */
    public AgentConfig withSubagents(Subagents subagents) {
        Objects.requireNonNull(subagents, "subagents must not be null");
        if (subagents.isEmpty()) {
            throw new IllegalArgumentException("Subagents has no subagents and self-cloning is off");
        }
        return new AgentConfig(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, toolApprovalHandler, toolPermissionPolicy, contextStrategy,
                subagents, parallelToolCalls, streaming);
    }

    /** A copy for running a subagent: its own prompt, tools and model, everything else inherited. */
    AgentConfig forSubagent(String systemPrompt, List<AgentTool> tools, ModelClient modelClient, Subagents subagents) {
        return new AgentConfig(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, toolApprovalHandler, toolPermissionPolicy, contextStrategy,
                subagents, parallelToolCalls, streaming);
    }

    /**
     * Returns a copy that runs the tools of one model reply at the same time when the model asks
     * for several at once, instead of one after another. Permission checks and approvals still
     * happen in order, and results are added in the order the model asked for them. Only enable
     * this if your tools are safe to run concurrently (for example, they do not change the same
     * session data).
     */
    public AgentConfig withParallelToolCalls(boolean parallel) {
        return new AgentConfig(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, toolApprovalHandler, toolPermissionPolicy, contextStrategy,
                subagents, parallel, streaming);
    }

    /**
     * Returns a copy that asks the model for each reply in one piece ({@code false}) instead of
     * streaming it (the default). Without streaming, listeners get no {@code onAssistantDelta}
     * calls; everything else works the same. Useful for batch jobs, and for models or gateways
     * that do not support streaming.
     */
    public AgentConfig withStreaming(boolean streaming) {
        return new AgentConfig(systemPrompt, modelClient, tools, maxIterations, toolErrorStrategy, runTimeout,
                maxToolArgumentLength, modelRetryPolicy, toolApprovalHandler, toolPermissionPolicy, contextStrategy,
                subagents, parallelToolCalls, streaming);
    }

    public boolean isStreaming() {
        return streaming;
    }

    public boolean isParallelToolCalls() {
        return parallelToolCalls;
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

    public ToolPermissionPolicy getToolPermissionPolicy() {
        return toolPermissionPolicy;
    }

    public ContextStrategy getContextStrategy() {
        return contextStrategy;
    }

    /** The subagents this agent can delegate to, or null if it cannot delegate. */
    public Subagents getSubagents() {
        return subagents;
    }
}
