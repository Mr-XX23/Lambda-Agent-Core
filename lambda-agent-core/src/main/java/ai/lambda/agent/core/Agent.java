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
import org.json.JSONObject;

public final class Agent {

    private final AgentConfig config;
    private final SessionStore sessionStore;
    private final Map<String, AgentTool> toolRegistry;
    private final List<ToolSchema> toolSchemas;
    private final List<AgentEventListener> listeners = new ArrayList<>();

    public Agent(AgentConfig config, SessionStore sessionStore) {
        this.config = config;
        this.sessionStore = sessionStore;
        this.toolRegistry = buildToolRegistry(config.getTools());
        this.toolSchemas = buildToolSchemas(config.getTools());
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
        Instant deadline = Instant.now().plus(config.getRunTimeout());
        for (AgentEventListener listener : listeners) listener.onRunStart(runId, sessionId);

        AgentSession session = sessionStore.loadOrCreate(sessionId);

        // Ensure system message is present once at the start.
        if (session.getMessages().isEmpty()) {
            session.getMessages().add(new Message(
                    Role.SYSTEM,
                    config.getSystemPrompt(),
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

            // Call the model with the current conversation and tool schemas, using streaming.
            ChatResponse response = callModelWithRetry(session, cancellationToken, deadline);
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
                    // Unknown tool: provide an error message back to the model.
                    String msg = "Tool '" + call.getName() + "' is not available.";

                    // Add a TOOL message with the error content.
                    var toolMessage = new Message(
                            Role.TOOL,
                            msg,
                            call.getId()
                    );

                    // Add the error message to the session so the model can see it in the next turn.
                    session.getMessages().add(toolMessage);

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

                for (AgentEventListener l : listeners) l.onToolStart(call, ctx);

                // Execute the tool and get the result.
                ToolResult result;

                try {

                    // Execute the tool and get the result.
                    result = tool.execute(ctx);

                    for (AgentEventListener l : listeners) l.onToolEnd(call, result);

                } catch (Exception e) {

                    for (AgentEventListener l : listeners) l.onToolError(call, e);

                    // Check the configured tool error strategy.
                    if (config.getToolErrorStrategy() == ToolErrorStrategy.THROW) {
                        throw new RuntimeException("Tool '" + call.getName() + "' failed", e);
                    }

                    // SEND_TO_MODEL: send a textual error back to the model.
                    String errorContent = "[lambda-agent-core] Tool '" + call.getName() + "' failed: " + e.getMessage();

                    // Add a TOOL message with the error content.
                    var toolMessage = new Message(
                            Role.TOOL,
                            errorContent,
                            call.getId()
                    );

                    // Add the error message to the session so the model can see it in the next turn.
                    session.getMessages().add(toolMessage);

                    continue;
                }

                // Add the tool result as a TOOL message in the conversation, so the model can see the result in the next turn.
                var toolMessage = new Message(
                        Role.TOOL,
                        result.getContent(),
                        call.getId(),
                        call.getName(),
                        null
                );
                session.getMessages().add(toolMessage);
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
    }

    private ChatResponse callModelWithRetry(AgentSession session, CancellationToken cancellationToken,
                                            Instant deadline) {
        RetryPolicy policy = config.getModelRetryPolicy();
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            cancellationToken.throwIfCancelled();
            try {
                return config.getModelClient().streamChat(
                        session.getMessages(),
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
