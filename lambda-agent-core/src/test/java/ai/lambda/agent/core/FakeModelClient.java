package ai.lambda.agent.core;

import ai.lambda.ai.core.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * A ModelClient that replays scripted responses and records what it was sent.
 * Token count is simply the number of characters, which keeps strategy tests predictable.
 */
final class FakeModelClient implements ModelClient {

    final List<List<Message>> requests = new ArrayList<>();
    final java.util.concurrent.atomic.AtomicInteger tokenCountCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final Deque<ChatResponse> script = new ArrayDeque<>();

    FakeModelClient reply(ChatResponse response) {
        script.add(response);
        return this;
    }

    FakeModelClient replyText(String text) {
        return reply(new ChatResponse(new Message(Role.ASSISTANT, text, null), List.of()));
    }

    // Like some real clients, puts the calls on the ChatResponse but not on the message.
    FakeModelClient replyToolCall(String id, String name, String argsJson) {
        return reply(new ChatResponse(new Message(Role.ASSISTANT, "", null), List.of(new ToolCall(id, name, argsJson))));
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolSchema> toolSchemas) {
        requests.add(List.copyOf(messages));
        if (script.isEmpty()) throw new AssertionError("FakeModelClient ran out of scripted responses");
        return script.poll();
    }

    @Override
    public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
        ChatResponse response = chat(messages, tools);
        String text = response.getAssistantMessage().getContent();
        if (onDelta != null && !text.isEmpty()) onDelta.accept(text);
        return response;
    }

    @Override
    public int countTokens(List<Message> messages) {
        tokenCountCalls.incrementAndGet();
        return messages.stream().mapToInt(m -> m.getContent().length()).sum();
    }
}
