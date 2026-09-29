package ai.lambda.examples.mcpAgent;

import ai.lambda.agent.core.*;
import ai.lambda.agent.mcp.McpServer;
import ai.lambda.ai.client.GoogleModelClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Scanner;

/**
 * Example: an agent that uses the tools of an MCP server.
 *
 * It starts the official filesystem MCP server (needs Node.js for npx) on ./sandbox.
 * Reading runs freely; every tool that changes something asks you first.
 *
 * Try:
 *   "What files are in the sandbox, and what's on my shopping list?"
 *   "Add bread to my shopping list"   (you will be asked to approve the write)
 */
public final class McpAgentExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }
        var model = new GoogleModelClient(apiKey, "gemini-3.1-flash-lite-preview");
        String sandbox = Path.of("sandbox").toAbsolutePath().toString();
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");

        System.out.println("Starting the filesystem MCP server on " + sandbox + " ...");
        try (Scanner scanner = new Scanner(System.in);
             McpServer files = McpServer.stdio("files", windows ? "npx.cmd" : "npx",
                             "-y", "@modelcontextprotocol/server-filesystem", sandbox)
                     .requireApprovalForWrites(true)
                     .connect()) {

            System.out.println("Connected to " + files.serverInfo() + " with tools:");
            files.tools().forEach(t -> System.out.println("  - " + t.getName() + " " + t.getCapabilities()));

            // Ask on the console before any tool that changes something runs.
            ToolApprovalHandler askUser = (sessionId, call) -> {
                System.out.print("  Allow " + call.getName() + " " + call.getArgumentsJson() + "? (y/n) ");
                return scanner.nextLine().trim().equalsIgnoreCase("y");
            };

            var config = new AgentConfig("You manage files in the user's sandbox folder: " + sandbox,
                    model, files.tools(), 10, ToolErrorStrategy.SEND_TO_MODEL, Duration.ofMinutes(5),
                    64 * 1024, RetryPolicy.none(), askUser);
            var agent = new Agent(config, new InMemorySessionStore());

            System.out.println("Type 'exit' to quit.");
            while (true) {
                System.out.print("\nYou: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) break;
                System.out.println("Agent: " + agent.run("mcp-session", line).getFinalText());
            }
        }
    }
}
