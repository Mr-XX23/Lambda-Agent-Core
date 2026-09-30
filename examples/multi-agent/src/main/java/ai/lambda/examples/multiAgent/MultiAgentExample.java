package ai.lambda.examples.multiAgent;

import ai.lambda.agent.core.*;
import ai.lambda.agent.prebuilt.FileReadTool;
import ai.lambda.ai.gemini.GeminiModelClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Example: a lead agent that breaks work into tasks and hands them to subagents.
 *
 *  - agents/code-reviewer.md and agents/doc-writer/agent.md define two specialists.
 *  - Self-cloning lets the lead start copies of itself for independent parts of a big task.
 *  - Every agent can only read files inside ./workspace.
 *
 * Try:
 *   "Review every file in the workspace for bugs and write docs for each class"
 */
public final class MultiAgentExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }
        var model = new GeminiModelClient(apiKey, "gemini-3.1-flash-lite-preview");

        Agent agent = new Agent(config(model), new InMemorySessionStore());
        agent.addListener(new AgentEventListener() {
            // Subagents run in parallel, so these can be called from several threads.
            @Override
            public synchronized void onSubagentStart(String subagent, String task, int depth) {
                System.out.println("  " + "  ".repeat(depth - 1) + "-> " + subagent + ": " + shorten(task));
            }

            @Override
            public synchronized void onSubagentEnd(String subagent, int depth, AgentResult result) {
                System.out.println("  " + "  ".repeat(depth - 1) + "<- " + subagent + " done ("
                        + result.getIterations() + " steps)");
            }

            @Override
            public synchronized void onSubagentError(String subagent, int depth, Exception error) {
                System.out.println("  " + "  ".repeat(depth - 1) + "!! " + subagent + " failed: " + error.getMessage());
            }
        });

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Multi-agent example. Working on ./workspace. Type 'exit' to quit.");
            while (true) {
                System.out.print("\nYou: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) break;

                AgentResult result = agent.run("lead", line);
                System.out.println("\nLead: " + result.getFinalText());
            }
        }
    }

    static AgentConfig config(ai.lambda.ai.core.ModelClient model) {
        Path workspace = Path.of("workspace");
        List<AgentTool> tools = List.of(new ListFilesTool(workspace), new FileReadTool(workspace));

        Subagents subagents = Subagents.load(Path.of("agents"))
                .withSelfCloning(true)
                .withMaxParallel(4);

        return new AgentConfig("""
                You are the lead engineer. For requests that cover several files or several kinds of work, \
                list the files, split the work into independent tasks, and delegate them in one \
                invoke_subagent call. Then combine the results into one clear answer.""",
                model, tools, 10).withSubagents(subagents);
    }

    private static String shorten(String text) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 90 ? oneLine : oneLine.substring(0, 87) + "...";
    }
}
