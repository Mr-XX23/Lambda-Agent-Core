package ai.lambda.examples.streamingChat;

import ai.lambda.ai.client.GoogleModelClient;
import ai.lambda.agent.core.*;
import java.io.IOException;
import java.util.Scanner;

public final class StreamingChatExample {

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }

        var modelClient = new GoogleModelClient(apiKey, "gemini-3.1-flash-lite-preview");

        var config = new AgentConfig(
                "You are Lambda, a helpful assistant. Please give long detailed responses when asked.",
                modelClient
        );

        var sessionStore = new InMemorySessionStore();
        var agent = new Agent(config, sessionStore);

        // Add a listener to print deltas
        agent.addListener(new AgentEventListener() {
            @Override
            public void onAssistantDelta(String delta) {
                System.out.print(delta);
                System.out.flush();
            }
        });

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Lambda Streaming Chat. Type 'exit' to quit.");
            while (true) {
                System.out.print("\nYou: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    break;
                }
                System.out.print("Lambda: ");
                agent.run("session-streaming", line);
                System.out.println();
            }
        }
    }
}
