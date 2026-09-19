package ai.lambda.examples.softwareDelivery;

import ai.lambda.ai.client.GoogleModelClient;
import ai.lambda.agent.core.*;
import ai.lambda.agent.prebuilt.FileReadTool;
import ai.lambda.agent.prebuilt.ProcessTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * A guarded software-delivery agent reference application.
 *
 * The model handles repository questions and proposes changes. The workflow
 * separates inspection, verification, and human approval so write-capable
 * tools can be added without making the first model response trusted.
 */
public final class SoftwareDeliveryAgent {
    private SoftwareDeliveryAgent() {
    }

    public static void main(String[] args) {
        Path repository = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(repository)) {
            throw new IllegalArgumentException("Repository directory does not exist: " + repository);
        }

        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Set GEMINI_API_KEY before starting the agent.");
            return;
        }

        var model = new GoogleModelClient(apiKey, "gemini-3.1-flash-lite-preview");
        var tools = List.<AgentTool>of(
                new FileReadTool(repository),
                new ProcessTool(Set.of("mvn -q test", "mvn -q verify", "git diff --check"))
        );
        var config = new AgentConfig(
                """
                You are a senior software-delivery agent. Inspect the repository before making
                recommendations. Use file reads and allowlisted checks only. Never claim that
                code changed unless a write operation was explicitly approved by a human.
                Explain risks, tests, and rollback considerations.
                """,
                model, tools, 8, ToolErrorStrategy.SEND_TO_MODEL,
                Duration.ofMinutes(5), 64 * 1024, RetryPolicy.none(),
                (sessionId, call) -> "true".equalsIgnoreCase(System.getenv("APPROVE_TOOL_CALLS")),
                ToolPermissionPolicy.allowAll()
        );
        var agent = new Agent(config, new InMemorySessionStore());

        var workflow = new Workflow(
                "software-delivery-review",
                List.of(
                        new WorkflowStepSpec("repository-baseline",
                                (context, token) -> inspect(repository, context)),
                        new WorkflowStepSpec("parallel-verification", Workflow.parallel("verification",
                                List.of(
                                        new WorkflowStepSpec("git-state",
                                                (context, token) -> context.put("gitState", "inspect with git diff --check")),
                                        new WorkflowStepSpec("build-state",
                                                (context, token) -> context.put("buildState", "run mvn -q test"))
                                ))),
                        new WorkflowStepSpec("approval",
                                new ApprovalWorkflowStep("approve-write-capable-actions",
                                        (executionId, stepName, context) ->
                                                "true".equalsIgnoreCase(System.getenv("APPROVE_WORKFLOW")))),
                        new WorkflowStepSpec("handoff",
                                (context, token) -> context.put("handoff",
                                        "Approved for the next implementation stage"))
                ),
                new InMemoryCheckpointStore(), true
        );

        System.out.println("Software Delivery Agent");
        System.out.println("Repository: " + repository);
        System.out.println("Workflow status: " + workflow.run("review-" + System.currentTimeMillis(),
                Map.of("repository", repository.toString()), new CancellationToken()).status());
        System.out.println("Ask repository questions. Type 'exit' to quit.");
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String input = scanner.nextLine();
                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }
                System.out.println(agent.run("software-delivery-session", input).getFinalText());
            }
        }
    }

    private static void inspect(Path repository, WorkflowContext context) throws Exception {
        long files = Files.walk(repository)
                .filter(Files::isRegularFile)
                .filter(path -> !path.toString().contains(Path.of(".git").toString()))
                .count();
        context.put("fileCount", files);
        context.put("repositoryReady", true);
    }
}
