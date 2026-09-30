package ai.lambda.examples.skillsAgent;

import ai.lambda.ai.gemini.GeminiModelClient;
import ai.lambda.agent.core.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Example: an agent that uses skills from the ./skills folder.
 *
 * Try:
 *   "Write a commit message: I added retries to the HTTP client and fixed a timeout bug"
 *   "Write release notes for 1.2.0: added skills, fixed Gemini tool calls, faster trimming"
 */
public final class SkillsAgentExample {

    public static void main(String[] args) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Please set GEMINI_API_KEY environment variable.");
            return;
        }

        var modelClient = new GeminiModelClient(apiKey, "gemini-3.1-flash-lite-preview");

        // Each subfolder of ./skills with a SKILL.md is one skill.
        Skills skills = Skills.load(Path.of("skills"));

        var config = new AgentConfig("You are a helpful assistant for software developers.",
                modelClient, List.of(), 8).withSkills(skills);

        var agent = new Agent(config, new InMemorySessionStore());

        // Show when the agent opens a skill or one of its files.
        agent.addListener(new AgentEventListener() {
            @Override
            public void onToolStart(ai.lambda.ai.core.ToolCall call, ToolInvocationContext ctx) {
                System.out.println("  [" + call.getName() + "] " + call.getArgumentsJson());
            }
        });

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Skills agent. Loaded skills:");
            skills.all().forEach(s -> System.out.println("  - " + s.name()));
            System.out.println("Type 'exit' to quit.");
            while (true) {
                System.out.print("\nYou: ");
                String line = scanner.nextLine();
                if ("exit".equalsIgnoreCase(line)) break;

                AgentResult result = agent.run("skills-session", line);
                System.out.println("Agent: " + result.getFinalText());
            }
        }
    }
}
