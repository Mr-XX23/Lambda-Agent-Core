package ai.lambda.examples.historyTruncation;

import ai.lambda.ai.gemini.GeminiModelClient;
import ai.lambda.agent.core.*;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public final class HistoryTruncationExample {

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("GEMINI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }
        var modelClient = new GeminiModelClient(apiKey, "gemini-3.1-flash-lite-preview");

        // STEP: Configure SlidingWindowStrategy to keep only the 3 most recent messages (+ System Prompt)
        var contextStrategy = new SlidingWindowStrategy(4); 

        var config = new AgentConfig(
                "You are Lambda, a memory-challenged assistant. You only remember the very last things we talked about.",
                modelClient,
                List.of(),
                8,
                ToolErrorStrategy.SEND_TO_MODEL,
                contextStrategy
        );

        var sessionStore = new InMemorySessionStore();
        var agent = new Agent(config, sessionStore);

        // Add a listener to observe what the model sees (after truncation)
        agent.addListener(new AgentEventListener() {
            @Override
            public void onIterationStart(int iteration) {
                System.out.println("\n[Iteration " + iteration + "] History size before model call: " 
                    + sessionStore.loadOrCreate("session-truncation").getMessages().size());
            }
        });

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Lambda History Truncation Chat (Limit: 4 messages).");
            while (true) {
                System.out.print("\nYou: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    break;
                }
                System.out.print("Lambda: ");
                agent.run("session-truncation", line);
                System.out.println();
            }
        }
    }
}
