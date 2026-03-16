package ai.lambda.examples.simpleChatWithToolCallingWithFileBasedSession;

import ai.lambda.ai.client.GoogleModelClient;

import ai.lambda.agent.core.Agent;
import ai.lambda.agent.core.AgentConfig;
import ai.lambda.agent.core.AgentResult;
import ai.lambda.agent.core.AgentTool;
import ai.lambda.agent.core.ToolInvocationContext;
import ai.lambda.agent.core.AgentEventListener;
import ai.lambda.agent.core.JsonlSessionStore;
import ai.lambda.agent.prebuilt.EchoTool;
import ai.lambda.agent.prebuilt.FileReadTool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class SimpleChatWithToolCallingWithFileBasedSession {

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

        // STEP 2.5: Add Tools
        List<AgentTool> tools = List.of(
                new EchoTool(),
                new FileReadTool()
        );

        // STEP 4: Persistent JSONL Session
        Path storagePath = Path.of(System.getProperty("user.dir"), ".sessions");
        var sessionStore = new JsonlSessionStore(storagePath);

        // STEP: 3 : Create AgentConfig with system prompt and ModelClient
        var config = new AgentConfig(
                "You are Lambda, a concise helpful assistant. You have access to two tools: 'echo' that echoes back text, and 'file-read' that reads the content of a file given its path. Use them to assist the user when needed.",
                modelClient,
                tools,
                3
        );

        // STEP: 5 : Create Agent instance
        var agent = new Agent(config, sessionStore);

        // Step: 5.1 : Add visibility via Listeners
        agent.addListener(new AgentEventListener() {
            @Override
            public void onToolStart(ai.lambda.ai.core.ToolCall call, ToolInvocationContext ctx) {
                System.out.println("  [⚙️ Executing Tool: " + call.getName() + "]");
            }
        });

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
