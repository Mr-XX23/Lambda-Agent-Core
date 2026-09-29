package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.Role;
import ai.lambda.ai.core.ToolCall;
import ai.lambda.ai.core.ToolSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class Agent {

    private final AgentConfig config;
    private final SessionStore sessionStore;
    private final Map<String, AgentTool> toolRegistry;
    private final List<ToolSchema> toolSchemas;
    // Thread-safe: subagents running in parallel report their events here.
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final String systemPrompt;

    public Agent(AgentConfig config, SessionStore sessionStore) {
        this(config, sessionStore, 0, config, null);
    }

    /**
     * @param depth      0 for the main agent, 1 for its subagents, and so on
     * @param rootConfig the main agent's config, which subagents are built from
     * @param observers  listeners that receive subagent events (the main agent's), or null for this agent's own
     */
    Agent(AgentConfig config, SessionStore sessionStore, int depth, AgentConfig rootConfig,
          List<AgentEventListener> observers) {
        this.config = config;
        this.sessionStore = sessionStore;

        List<AgentTool> tools = new ArrayList<>(config.getTools());
        String prompt = config.getSystemPrompt();
        Subagents subagents = config.getSubagents();
        if (subagents != null) {
            if (depth == 0) checkSubagentTools(subagents, config.getTools());
            if (depth < subagents.maxDepth()) {
                tools.add(new InvokeSubagentTool(rootConfig, subagents, depth,
                        observers != null ? observers : listeners));
                prompt = prompt + "\n\n" + subagents.promptSection();
            }
        }
        this.systemPrompt = prompt;
        this.toolRegistry = buildToolRegistry(tools);
        this.toolSchemas = buildToolSchemas(tools);
    }

    // A subagent can only be given tools the main agent has, so its permissions never exceed the main agent's.
    private static void checkSubagentTools(Subagents subagents, List<AgentTool> tools) {
        List<String> available = tools.stream().map(AgentTool::getName).toList();
        for (Subagent subagent : subagents.all()) {
            for (String tool : subagent.tools()) {
                if (!available.contains(tool)) {
                    throw new IllegalArgumentException("Subagent '" + subagent.name() + "' uses tool '" + tool
                            + "', which the agent does not have. Add the tool to the agent's tools too.");
                }
            }
        }
    }

    private static Map<String, AgentTool> buildToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new HashMap<>();
        for (AgentTool tool : tools) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                throw new IllegalArgumentException("Every tool must have a non-blank name");
            }
            if (map.put(tool.getName(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.getName());
            }
        }
        return Map.copyOf(map);
    }

    private static List<ToolSchema> buildToolSchemas(List<AgentTool> tools) {
        return tools.stream()
                .map(t -> {
                    String schema = t.getJsonSchema();
                    if (schema == null || schema.isBlank()) {
                        throw new IllegalArgumentException("Tool '" + t.getName() + "' must define a JSON schema");
                    }
                    try {
                        new JSONObject(schema);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Tool '" + t.getName() + "' has invalid JSON schema", e);
                    }
                    return new ToolSchema(t.getName(), t.getDescription(), schema);
                })
                .toList();
    }

    public void addListener(AgentEventListener listener) {
        this.listeners.add(listener);
    }

    public AgentResult run(String sessionId, String userInput) {
        return run(sessionId, userInput, new CancellationToken());
    }

    public AgentResult run(String sessionId, String userInput, CancellationToken cancellationToken) {
        String runId = UUID.randomUUID().toString();
        TraceContext.activate(runId);
        try {
            return runInternal(sessionId, userInput, cancellationToken, runId);
        } finally {
            TraceContext.clear();
        }
    }

    private AgentResult runInternal(String sessionId, String userInput,
                                    CancellationToken cancellationToken, String runId) {
        Instant deadline = Instant.now().plus(config.getRunTimeout());
        for (AgentEventListener listener : listeners) listener.onRunStart(runId, sessionId);

        AgentSession session = sessionStore.loadOrCreate(sessionId);

        try {
        // Ensure system message is present once at the start.
        if (session.getMessages().isEmpty()) {
            session.getMessages().add(new Message(
                    Role.SYSTEM,
                    systemPrompt,
                    null
            ));
        }

        // Add the new user message.
        session.getMessages().add(new Message(
                Role.USER,
                userInput,
                null
        ));

        // Core agent loop: call model, possibly execute tools, repeat.
        for (int iteration  = 0; iteration  < config.getMaxIterations(); iteration++) {
            cancellationToken.throwIfCancelled();
            if (Instant.now().isAfter(deadline)) {
                throw new RuntimeException(new TimeoutException("Agent run exceeded " + config.getRunTimeout()));
            }

            for (AgentEventListener l : listeners) l.onIterationStart(iteration);

            // Trim what the model sees (the session keeps the full history), then call it with streaming.
            List<Message> history = config.getContextStrategy().optimize(
                    session.getMessages(), config.getModelClient());
            ChatResponse response = callModelWithRetry(history, cancellationToken, deadline);
            for (AgentEventListener listener : listeners) listener.onModelResponse(response);

            // Add the assistant's response to the conversation.
            Message assistant = response.getAssistantMessage();
            List<ToolCall> calls = response.getToolCalls();
            if (calls != null && !calls.isEmpty() && assistant.getToolCalls().isEmpty()) {
                assistant = new Message(
                        assistant.getRole(),
                        assistant.getContent(),
                        assistant.getToolCallId(),
                        assistant.getToolCallName(),
                        calls
                );
            }
            session.getMessages().add(assistant);

            for (AgentEventListener l : listeners) l.onAssistantMessage(assistant);

            // Check if the model requested any tool calls.
            if (calls == null || calls.isEmpty()) {
                // No tools requested -> we're done
                sessionStore.save(session);
                AgentResult result = new AgentResult(assistant.getContent(), session, runId, iteration + 1);
                for (AgentEventListener listener : listeners) listener.onRunEnd(result);
                return result;
            }

            // Execute each requested tool and add TOOL messages.
            for (ToolCall call : calls) {
                AgentTool tool = toolRegistry.get(call.getName());

                if (tool == null) {
                    // Unknown tool: provide an error message back to the model so it can see it in the next turn.
                    session.getMessages().add(toolMessage(call, "Tool '" + call.getName() + "' is not available."));
                    continue;
                }
                ToolPolicy policy = tool.getPolicy();
                PermissionDecision permission = config.getToolPermissionPolicy()
                        .evaluate(sessionId, tool, tool.getCapabilities());
                if (!permission.allowed()) {
                    audit(sessionId, call, tool, "DENIED", permission.reason());
                    session.getMessages().add(toolMessage(call,
                            "Tool '" + call.getName() + "' was denied: " + permission.reason()));
                    continue;
                }
                audit(sessionId, call, tool, "AUTHORIZED", permission.reason());
                if (policy.requiresApproval()
                        && !config.getToolApprovalHandler().approve(sessionId, call)) {
                    audit(sessionId, call, tool, "DENIED", "human approval was not granted");
                    session.getMessages().add(toolMessage(call, "Tool '" + call.getName() + "' was not approved."));
                    continue;
                }

                // Create the context and execute the tool.
                ToolInvocationContext ctx = new ToolInvocationContext(
                        call.getId(),
                        call.getArgumentsJson(),
                        session
                );
                if (call.getArgumentsJson() != null
                        && call.getArgumentsJson().length() > config.getMaxToolArgumentLength()) {
                    throw new IllegalArgumentException("Tool arguments exceed configured limit");
                }
                try {
                    tool.getArgumentValidator().validate(call.getArgumentsJson());
                    if (tool.getTypedInputSchema() != null) {
                        tool.getTypedInputSchema().parse(call.getArgumentsJson());
                    }
                } catch (Exception validationError) {
                    audit(sessionId, call, tool, "DENIED", "typed input rejected: "
                            + validationError.getMessage());
                    session.getMessages().add(toolMessage(call,
                            "Tool '" + call.getName() + "' arguments were rejected: "
                                    + validationError.getMessage()));
                    continue;
                }

                for (AgentEventListener l : listeners) l.onToolStart(call, ctx);

                // Execute the tool and get the result.
                ToolResult result;

                try {

                    // Execute the tool and get the result.
                    {
                        java.util.concurrent.Future<ToolResult> future = toolExecutor.submit(() -> tool.execute(ctx));
                        try {
                            result = future.get(policy.timeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.TimeoutException timeout) {
                            future.cancel(true);
                            throw new RuntimeException("Tool '" + call.getName() + "' timed out", timeout);
                        } finally {
                            future.cancel(false);
                        }
                    }

                    for (AgentEventListener l : listeners) l.onToolEnd(call, result);

                } catch (Exception e) {

                    for (AgentEventListener l : listeners) l.onToolError(call, e);

                    // Check the configured tool error strategy.
                    if (config.getToolErrorStrategy() == ToolErrorStrategy.THROW) {
                        throw new RuntimeException("Tool '" + call.getName() + "' failed", e);
                    }

                    // SEND_TO_MODEL: send a textual error back to the model so it can see it in the next turn.
                    String errorContent = "[lambda-agent-core] Tool '" + call.getName() + "' failed: " + e.getMessage();
                    session.getMessages().add(toolMessage(call,
                            Truncation.keepHeadAndTail(errorContent, policy.maxResultLength())));
                    continue;
                }

                // Add the tool result so the model can see it in the next turn. Oversized results keep
                // their start and end; listeners above already received the full text.
                session.getMessages().add(toolMessage(call,
                        Truncation.keepHeadAndTail(result.getContent(), policy.maxResultLength())));
            }

        }

        // Safety stop: too many iterations.
        sessionStore.save(session);
        AgentResult result = new AgentResult(
                "[lambda-agent-core] Stopped after max iterations.",
                session, runId, config.getMaxIterations()
        );
        for (AgentEventListener listener : listeners) listener.onRunEnd(result);
        return result;
        } catch (RuntimeException | Error failure) {
            try {
                sessionStore.save(session);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            throw failure;
        }
    }

    // Every TOOL message carries the call's id and tool name: providers match results to calls
    // with them (Gemini needs the name, OpenAI the id).
    private static Message toolMessage(ToolCall call, String content) {
        return new Message(Role.TOOL, content, call.getId(), call.getName(), null);
    }

    private void audit(String sessionId, ToolCall call, AgentTool tool, String action, String reason) {
        ToolAuditEvent event = new ToolAuditEvent(Instant.now(), sessionId, tool.getName(),
                call.getId(), action, reason, tool.getCapabilities());
        for (AgentEventListener listener : listeners) listener.onToolAudit(event);
    }

    private ChatResponse callModelWithRetry(List<Message> history, CancellationToken cancellationToken,
                                            Instant deadline) {
        RetryPolicy policy = config.getModelRetryPolicy();
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            cancellationToken.throwIfCancelled();
            try {
                return config.getModelClient().streamChat(
                        history,
                        toolSchemas,
                        delta -> {
                            for (AgentEventListener listener : listeners) {
                                listener.onAssistantDelta(delta);
                            }
                        });
            } catch (RuntimeException error) {
                if (attempt == policy.maxAttempts() || error instanceof java.util.concurrent.CancellationException) {
                    throw error;
                }
                Duration delay = policy.delayBeforeAttempt(attempt + 1);
                for (AgentEventListener listener : listeners) {
                    listener.onModelRetry(attempt + 1, error, delay);
                }
                sleepBeforeRetry(delay, cancellationToken, deadline);
            }
        }
        throw new IllegalStateException("Retry policy produced no model response");
    }

    private static void sleepBeforeRetry(Duration delay, CancellationToken cancellationToken,
                                         Instant deadline) {
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMillis <= 0 || delay.toMillis() > remainingMillis) {
            throw new RuntimeException(new TimeoutException("Agent run exceeded its deadline before model retry"));
        }
        try {
            long end = System.nanoTime() + delay.toNanos();
            while (true) {
                cancellationToken.throwIfCancelled();
                long remainingNanos = end - System.nanoTime();
                if (remainingNanos <= 0) {
                    return;
                }
                Thread.sleep(Math.max(1, Math.min(remainingNanos / 1_000_000L, 50)));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to retry model call", error);
        }
    }
}
