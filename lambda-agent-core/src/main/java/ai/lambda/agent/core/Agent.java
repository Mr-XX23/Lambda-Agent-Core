package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Media;
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
        return runWithTrace(sessionId, new Message(Role.USER, userInput, null), cancellationToken, null).result();
    }

    /**
     * Runs the agent on text plus images, audio, video or documents, for example
     * {@code agent.run("s1", "What's in this photo?", Media.fromFile(Path.of("photo.jpg")))}.
     * The model client rejects media its model cannot take, with a clear message.
     */
    public AgentResult run(String sessionId, String userInput, Media... media) {
        return run(sessionId, Message.user(userInput, media));
    }

    /** Runs the agent on a user message (text and optional media). */
    public AgentResult run(String sessionId, Message userMessage) {
        return run(sessionId, userMessage, new CancellationToken());
    }

    public AgentResult run(String sessionId, Message userMessage, CancellationToken cancellationToken) {
        return runWithTrace(sessionId, requireUser(userMessage), cancellationToken, null).result();
    }

    private static Message requireUser(Message message) {
        java.util.Objects.requireNonNull(message, "userMessage must not be null");
        if (message.getRole() != Role.USER) {
            throw new IllegalArgumentException("Expected a USER message, got " + message.getRole());
        }
        return message;
    }

    /**
     * Runs the agent and returns its answer as a {@code T}. The model delivers the answer by
     * calling a {@code submit_result} tool; invalid answers are sent back to it to fix.
     *
     * @throws StructuredOutputException if there is no valid answer within the iteration limit
     */
    public <T> StructuredResult<T> run(String sessionId, String userInput, StructuredOutput<T> output) {
        return run(sessionId, userInput, output, new CancellationToken());
    }

    public <T> StructuredResult<T> run(String sessionId, String userInput, StructuredOutput<T> output,
                                       CancellationToken cancellationToken) {
        return run(sessionId, new Message(Role.USER, userInput, null), output, cancellationToken);
    }

    /** Like {@link #run(String, String, StructuredOutput)}, for a user message that may carry media. */
    public <T> StructuredResult<T> run(String sessionId, Message userMessage, StructuredOutput<T> output) {
        return run(sessionId, userMessage, output, new CancellationToken());
    }

    public <T> StructuredResult<T> run(String sessionId, Message userMessage, StructuredOutput<T> output,
                                       CancellationToken cancellationToken) {
        requireUser(userMessage);
        java.util.Objects.requireNonNull(output, "output must not be null");
        if (toolRegistry.containsKey(StructuredOutput.TOOL_NAME)) {
            throw new IllegalArgumentException("A tool is already named '" + StructuredOutput.TOOL_NAME
                    + "', which structured output needs");
        }
        RunOutcome outcome = runWithTrace(sessionId, userMessage, cancellationToken, output);
        @SuppressWarnings("unchecked")
        T value = (T) outcome.value();
        return new StructuredResult<>(value, outcome.result());
    }

    private record RunOutcome(AgentResult result, Object value) {
    }

    private RunOutcome runWithTrace(String sessionId, Message userMessage, CancellationToken cancellationToken,
                                    StructuredOutput<?> output) {
        String runId = UUID.randomUUID().toString();
        TraceContext.activate(runId);
        try {
            return runInternal(sessionId, userMessage, cancellationToken, runId, output);
        } finally {
            TraceContext.clear();
        }
    }

    private RunOutcome runInternal(String sessionId, Message userMessage,
                                   CancellationToken cancellationToken, String runId,
                                   StructuredOutput<?> output) {
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
        session.getMessages().add(userMessage);

        // With structured output, the model also gets the submit_result tool.
        List<ToolSchema> runTools = toolSchemas;
        if (output != null) {
            runTools = new ArrayList<>(toolSchemas);
            AgentTool submit = output.tool();
            runTools.add(new ToolSchema(submit.getName(), submit.getDescription(), submit.getJsonSchema()));
        }
        List<String> lastProblems = List.of();

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
            ChatResponse response = callModelWithRetry(history, runTools, cancellationToken, deadline);
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
                if (output != null) {
                    // A plain-text reply is not an answer here: ask for the tool call.
                    session.getMessages().add(new Message(Role.USER, "Give your final answer by calling the "
                            + StructuredOutput.TOOL_NAME + " tool with it as the arguments. A plain-text reply "
                            + "is not accepted.", null));
                    continue;
                }
                // No tools requested -> we're done
                sessionStore.save(session);
                AgentResult result = new AgentResult(assistant.getContent(), session, runId, iteration + 1);
                for (AgentEventListener listener : listeners) listener.onRunEnd(result);
                return new RunOutcome(result, null);
            }

            // Execute each requested tool and add TOOL messages.
            Object accepted = null;
            String acceptedJson = null;
            List<Object> slots = new ArrayList<>(); // a TOOL Message, or a PendingTool still running
            try {
            for (ToolCall call : calls) {
                if (cancellationToken.isCancelled()) {
                    // Every call still gets a result, so the saved history stays valid for the next run.
                    slots.add(toolMessage(call, CANCELLED_NOTE));
                    continue;
                }
                if (output != null && call.getName().equals(StructuredOutput.TOOL_NAME)) {
                    if (acceptedJson != null) {
                        slots.add(toolMessage(call, "Ignored: an answer was already accepted."));
                        continue;
                    }
                    try {
                        if (call.getArgumentsJson() != null
                                && call.getArgumentsJson().length() > config.getMaxToolArgumentLength()) {
                            throw new StructuredOutput.InvalidOutputException(List.of(
                                    "$: the answer is longer than the limit of " + config.getMaxToolArgumentLength()
                                            + " characters"));
                        }
                        accepted = output.parse(call.getArgumentsJson());
                        acceptedJson = call.getArgumentsJson();
                        slots.add(toolMessage(call, "Answer accepted."));
                    } catch (StructuredOutput.InvalidOutputException invalid) {
                        lastProblems = invalid.problems();
                        slots.add(toolMessage(call, "The answer was rejected. Fix these problems "
                                + "and call " + StructuredOutput.TOOL_NAME + " again:\n- "
                                + String.join("\n- ", invalid.problems())));
                    }
                    continue;
                }

                AgentTool tool = toolRegistry.get(call.getName());

                if (tool == null) {
                    // Unknown tool: provide an error message back to the model so it can see it in the next turn.
                    slots.add(toolMessage(call, "Tool '" + call.getName() + "' is not available."));
                    continue;
                }
                ToolPolicy policy = tool.getPolicy();
                PermissionDecision permission = config.getToolPermissionPolicy()
                        .evaluate(sessionId, tool, tool.getCapabilities());
                if (!permission.allowed()) {
                    audit(sessionId, call, tool, "DENIED", permission.reason());
                    slots.add(toolMessage(call,
                            "Tool '" + call.getName() + "' was denied: " + permission.reason()));
                    continue;
                }
                audit(sessionId, call, tool, "AUTHORIZED", permission.reason());
                if (policy.requiresApproval()
                        && !config.getToolApprovalHandler().approve(sessionId, call)) {
                    audit(sessionId, call, tool, "DENIED", "human approval was not granted");
                    slots.add(toolMessage(call, "Tool '" + call.getName() + "' was not approved."));
                    continue;
                }

                // Create the context and execute the tool.
                ToolInvocationContext ctx = new ToolInvocationContext(
                        call.getId(),
                        call.getArgumentsJson(),
                        session,
                        cancellationToken
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
                    slots.add(toolMessage(call,
                            "Tool '" + call.getName() + "' arguments were rejected: "
                                    + validationError.getMessage()));
                    continue;
                }

                for (AgentEventListener l : listeners) l.onToolStart(call, ctx);

                // A tool that may run alongside others starts right away and the loop moves on.
                // Any other tool first waits for the tools before it, then runs alone.
                // Results are added in the calls' order either way.
                boolean alongsideOthers = switch (config.getToolParallelism()) {
                    case ALWAYS -> true;
                    case NEVER -> false;
                    case AUTO -> tool.isParallelSafe();
                };
                if (alongsideOthers) finishOldestWhileAtLimit(slots, config.getMaxParallelTools());
                else finishAll(slots);
                PendingTool pending = new PendingTool(call, policy, toolExecutor.submit(() -> tool.execute(ctx)),
                        System.nanoTime() + policy.timeout().toNanos(), cancellationToken);
                slots.add(alongsideOthers ? pending : finish(pending));
            }

            // Collect results in the order the model asked for them.
            for (int i = 0; i < slots.size(); i++) {
                Object slot = slots.get(i);
                session.getMessages().add(slot instanceof PendingTool running ? finish(running) : (Message) slot);
            }
            cancellationToken.throwIfCancelled(); // the history is complete, so stop here
            } catch (RuntimeException | Error failure) {
                // Stop tools still running in parallel before giving up on this step.
                for (Object slot : slots) {
                    if (slot instanceof PendingTool running) running.future().cancel(true);
                }
                throw failure;
            }

            if (acceptedJson != null) {
                sessionStore.save(session);
                AgentResult result = new AgentResult(acceptedJson, session, runId, iteration + 1);
                for (AgentEventListener listener : listeners) listener.onRunEnd(result);
                return new RunOutcome(result, accepted);
            }
        }

        // Safety stop: too many iterations.
        sessionStore.save(session);
        AgentResult result = new AgentResult(
                "[lambda-agent-core] Stopped after max iterations.",
                session, runId, config.getMaxIterations()
        );
        for (AgentEventListener listener : listeners) listener.onRunEnd(result);
        if (output != null) {
            throw new StructuredOutputException("No valid answer after " + config.getMaxIterations() + " iterations"
                    + (lastProblems.isEmpty() ? "; the model never called " + StructuredOutput.TOOL_NAME
                            : "; last problems: " + String.join("; ", lastProblems)), result, lastProblems);
        }
        return new RunOutcome(result, null);
        } catch (RuntimeException | Error failure) {
            try {
                sessionStore.save(session);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            throw failure;
        }
    }

    private record PendingTool(ToolCall call, ToolPolicy policy,
                               java.util.concurrent.Future<ToolResult> future, long deadlineNanos,
                               CancellationToken cancellationToken) {
    }

    private static final String CANCELLED_NOTE = "[lambda-agent-core] Not completed: the run was cancelled.";

    /** How often a waiting run checks whether it was cancelled while a tool is running. */
    private static final long CANCEL_CHECK_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20);

    /** Waits for every tool still running and replaces it with its TOOL message. */
    private void finishAll(List<Object> slots) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) instanceof PendingTool running) slots.set(i, finish(running));
        }
    }

    /** Keeps the number of running tools below {@code limit} by waiting for the earliest ones. */
    private void finishOldestWhileAtLimit(List<Object> slots, int limit) {
        long running = slots.stream().filter(PendingTool.class::isInstance).count();
        for (int i = 0; i < slots.size() && running >= limit; i++) {
            if (slots.get(i) instanceof PendingTool oldest) {
                slots.set(i, finish(oldest));
                running--;
            }
        }
    }

    /** Waits for a started tool (within its timeout) and returns its TOOL message. */
    private Message finish(PendingTool pending) {
        ToolCall call = pending.call();
        ToolResult result;
        try {
            try {
                // Wait in short steps, so a cancelled run interrupts the tool at once instead of
                // waiting for it to finish or time out.
                while (true) {
                    if (pending.cancellationToken().isCancelled() && !pending.future().isDone()) {
                        pending.future().cancel(true);
                        for (AgentEventListener l : listeners) {
                            l.onToolError(call, new java.util.concurrent.CancellationException("the run was cancelled"));
                        }
                        return toolMessage(call, CANCELLED_NOTE);
                    }
                    long remaining = pending.deadlineNanos() - System.nanoTime();
                    if (remaining <= 0 && !pending.future().isDone()) {
                        pending.future().cancel(true);
                        throw new RuntimeException("Tool '" + call.getName() + "' timed out");
                    }
                    try {
                        result = pending.future().get(Math.max(0, Math.min(remaining, CANCEL_CHECK_NANOS)),
                                java.util.concurrent.TimeUnit.NANOSECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException stillRunning) {
                        // check for cancellation and the deadline again
                    }
                }
            } finally {
                pending.future().cancel(false);
            }
            for (AgentEventListener l : listeners) l.onToolEnd(call, result);
        } catch (Exception e) {
            for (AgentEventListener l : listeners) l.onToolError(call, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();

            // Check the configured tool error strategy.
            if (config.getToolErrorStrategy() == ToolErrorStrategy.THROW) {
                throw new RuntimeException("Tool '" + call.getName() + "' failed", e);
            }

            // SEND_TO_MODEL: send a textual error back to the model so it can see it in the next turn.
            String errorContent = "[lambda-agent-core] Tool '" + call.getName() + "' failed: " + e.getMessage();
            return toolMessage(call, Truncation.keepHeadAndTail(errorContent, pending.policy().maxResultLength()));
        }
        // Oversized results keep their start and end; listeners above already received the full text.
        return toolMessage(call, Truncation.keepHeadAndTail(result.getContent(), pending.policy().maxResultLength()));
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

    private ChatResponse callModelWithRetry(List<Message> history, List<ToolSchema> tools,
                                            CancellationToken cancellationToken, Instant deadline) {
        RetryPolicy policy = config.getModelRetryPolicy();
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            cancellationToken.throwIfCancelled();
            try {
                if (!config.isStreaming()) return config.getModelClient().chat(history, tools);
                return config.getModelClient().streamChat(
                        history,
                        tools,
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
