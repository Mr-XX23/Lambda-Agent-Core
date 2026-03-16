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
            map.put(tool.getName(), tool);
        }
        return Map.copyOf(map);
    }

    private static List<ToolSchema> buildToolSchemas(List<AgentTool> tools) {
        return tools.stream()
                .map(t -> new ToolSchema(t.getName(), t.getDescription(), t.getJsonSchema()))
                .toList();
    }

    public void addListener(AgentEventListener listener) {
        this.listeners.add(listener);
    }

    public AgentResult run(String sessionId, String userInput) {

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

            for (AgentEventListener l : listeners) l.onIterationStart(iteration);

            // Call the model with the current conversation and tool schemas.
            ChatResponse  response = config.getModelClient().chat(session.getMessages(), toolSchemas);

            // Add the assistant's response to the conversation.
            Message  assistant = response.getAssistantMessage();
            session.getMessages().add(assistant);

            for (AgentEventListener l : listeners) l.onAssistantMessage(assistant);

            // Check if the model requested any tool calls.
            List<ToolCall> calls = response.getToolCalls();

            if (calls == null || calls.isEmpty()) {
                // No tools requested -> we're done
                sessionStore.save(session);
                return new AgentResult(assistant.getContent(), session);
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
        return new AgentResult(
                "[lambda-agent-core] Stopped after max iterations.",
                session
        );
    }
}
