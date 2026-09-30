package ai.lambda.ai.core;

import java.util.List;
import java.util.function.Consumer;

/** A provider module as seen by Models, for testing Models without any real provider installed. */
public final class TestModelProviders implements ModelProvider.Registry {

    /** A client that remembers the key and model it was built with. */
    record FakeClient(String apiKey, String model) implements ModelClient {
        public ChatResponse chat(List<Message> messages, List<ToolSchema> tools) {
            return new ChatResponse(new Message(Role.ASSISTANT, "ok", null), List.of());
        }

        public ChatResponse streamChat(List<Message> messages, List<ToolSchema> tools, Consumer<String> onDelta) {
            return chat(messages, tools);
        }
    }

    @Override
    public List<ModelProvider> providers() {
        return List.of(
                new ModelProvider("fake", List.of("pretend"), "FAKE_API_KEY", "fake-default", FakeClient::new),
                new ModelProvider("local", List.of(), null, "local-default", FakeClient::new));
    }
}
