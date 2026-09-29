package ai.lambda.agent.core;

import ai.lambda.ai.core.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A thread-safe ModelClient that decides each reply from the request, so a main agent and
 * its subagents (running in parallel) can share one fake model.
 */
final class RoutingModelClient implements ModelClient {

    record Request(List<Message> messages, List<ToolSchema> tools) {
        String system() {
            return !messages.isEmpty() && messages.get(0).getRole() == Role.SYSTEM ? messages.get(0).getContent() : "";
        }

        String lastUser() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).getRole() == Role.USER) return messages.get(i).getContent();
            }
            return "";
        }

        /** Content of the latest TOOL message, or null if there is none. */
        String lastToolResult() {
            Message last = messages.get(messages.size() - 1);
            return last.getRole() == Role.TOOL ? last.getContent() : null;
        }

        List<String> toolNames() {
            return tools.stream().map(ToolSchema::getName).toList();
        }

        boolean isSubagent() {
            return system().contains("Working as a subagent");
        }
    }

    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final Function<Request, ChatResponse> brain;

    RoutingModelClient(Function<Request, ChatResponse> brain) {
        this.brain = brain;
    }

    static ChatResponse text(String text) {
        return new ChatResponse(new Message(Role.ASSISTANT, text, null), List.of());
    }

    static ChatResponse callTool(String name, String argsJson) {
        ToolCall call = new ToolCall(UUID.randomUUID().toString(), name, argsJson);
        return new ChatResponse(new Message(Role.ASSISTANT, "", null, null, List.of(call)), List.of(call));
    }

    List<Request> subagentRequests() {
        synchronized (requests) {
            return requests.stream().filter(Request::isSubagent).toList();
        }
    }

    List<Request> mainRequests() {
        synchronized (requests) {
            return requests.stream().filter(r -> !r.isSubagent()).toList();
        }
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
        Request request = new Request(List.copyOf(messages), tools == null ? List.of() : List.copyOf(tools));
        requests.add(request);
        return brain.apply(request);
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        return chat(messages, tools);
    }
}
