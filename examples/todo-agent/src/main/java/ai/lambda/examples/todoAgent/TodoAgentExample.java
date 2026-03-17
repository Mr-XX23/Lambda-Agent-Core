package ai.lambda.examples.todoAgent;

import ai.lambda.ai.client.GoogleModelClient;
import ai.lambda.agent.core.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Example: a stateful Todo Agent that persists session data using JSONL storage.
 *
 * Demonstrates:
 *  - AddTodoTool  — adds a todo item (stored in session metadata)
 *  - ListTodosTool — lists all current todo items
 *  - JsonlSessionStore — file-based session persistence
 *  - AgentEventListener — logging agent events
 */
public class TodoAgentExample {

    public static void main(String[] args) throws IOException {

        // 1. Get API key
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }

        // 2. Create model client
        var modelClient = new GoogleModelClient(apiKey, "gemini-3.1-flash-lite-preview");

        // 3. Create tools
        List<AgentTool> tools = List.of(new AddTodoTool(), new ListTodosTool());

        // 4. Create config
        var config = new AgentConfig(
                "You are a helpful Todo assistant. You can add and list todo items for the user. "
                        + "Use the 'add_todo' tool to add items and the 'list_todos' tool to show them.",
                modelClient,
                tools,
                5
        );

        // 5. Create JSONL-based session store for persistence
        var sessionStore = new JsonlSessionStore(Path.of("sessions"));

        // 6. Create agent
        var agent = new Agent(config, sessionStore);

        // 7. (Optional) Add an event listener for logging
        agent.addListener(new AgentEventListener() {
            @Override
            public void onToolStart(ai.lambda.ai.core.ToolCall call, ToolInvocationContext ctx) {
                System.out.println("  [event] Tool starting: " + call.getName());
            }

            @Override
            public void onToolEnd(ai.lambda.ai.core.ToolCall call, ToolResult result) {
                System.out.println("  [event] Tool finished: " + call.getName()
                        + " → " + result.getContent());
            }

            @Override
            public void onAssistantMessage(ai.lambda.ai.core.Message msg) {
                System.out.println("  [event] Assistant replied.");
            }
        });

        // 8. REPL loop
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Todo Agent. Type 'exit' to quit.");
            System.out.println("Try: \"Add buy groceries to my list\" or \"Show my todos\"");
            while (true) {
                System.out.print("You: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) break;

                AgentResult result = agent.run("todo-session", line);
                System.out.println("Agent: " + result.getFinalText());
            }
        }
    }
}
