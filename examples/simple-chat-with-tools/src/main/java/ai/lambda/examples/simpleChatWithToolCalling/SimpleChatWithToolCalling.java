package ai.lambda.examples.simpleChatWithToolCalling;

import ai.lambda.ai.client.GoogleModelClient;
import ai.lambda.agent.core.*;
import ai.lambda.agent.prebuilt.EchoTool;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

public class SimpleChatExample {

    public static void main(String[] args) throws IOException {

        // STEP: 1
        // 1.1 : Get API key from environment variable
        String apiKey = System.getenv("GEMINI_API_KEY");

        // 1.2 : Check if API key is set ( OPTIONAL but recommended for better error handling )
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }

        // STEP: 2 : Create ModelClient instance (GoogleModelClient for Gemini)
        var modelClient = new GoogleModelClient(apiKey, "gemini-3.1-flash-lite-preview");

        // STEP: 2.5 : (Optional) Create tools and add to config
        List<AgentTool> tools = List.of(new EchoTool());

        // STEP: 3 : Create AgentConfig with system prompt and ModelClient
        var config = new AgentConfig(
                "You are Lambda, a concise helpful assistant. You have access to a tool called 'echo' that echoes back text. Use it to repeat user input when needed.",
                modelClient,
                tools,
                3
        );

        // STEP: 4 : Create SessionStore (InMemorySessionStore for simplicity)
        var sessionStore = new InMemorySessionStore();

        // STEP: 5 : Create Agent instance
        var agent = new Agent(config, sessionStore);

        // STEP: 6 : Run a simple REPL loop to chat with the agent
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Lambda chat. Type 'exit' to quit.");
            while (true) {
                System.out.print("You: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) {
                    break;
                }
                AgentResult result = agent.run("session-1", line);
                System.out.println("Lambda: " + result.getFinalText());
            }
        }

    }

}
