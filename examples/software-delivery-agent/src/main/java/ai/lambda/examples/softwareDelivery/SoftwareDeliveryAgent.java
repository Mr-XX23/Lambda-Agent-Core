package ai.lambda.examples.softwareDelivery;

import ai.lambda.ai.client.GoogleModelClient;
import ai.lambda.agent.core.*;
import ai.lambda.agent.prebuilt.FileReadTool;
import ai.lambda.agent.prebuilt.ProcessTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

        var checkpoints = new InMemoryCheckpointStore();
        var workflow = new Workflow(
                "software-delivery-review",
                List.of(
                        new WorkflowStepSpec("repository-baseline",
                                (context, token) -> inspect(repository, context)),
                        new WorkflowStepSpec("parallel-verification", Workflow.parallel("verification",
                                List.of(
                                        new WorkflowStepSpec("git-state",
                                                (context, token) -> context.put("gitState",
                                                        runCheck(repository, List.of("git", "diff", "--check")))),
                                        new WorkflowStepSpec("build-state",
                                                (context, token) -> context.put("buildState",
                                                        runCheck(repository, List.of("mvn", "-q", "test")))
                                )))),
                        new WorkflowStepSpec("approval",
                                new ApprovalWorkflowStep("approve-write-capable-actions",
                                        (executionId, stepName, context) ->
                                                "true".equalsIgnoreCase(System.getenv("APPROVE_WORKFLOW")))),
                        new WorkflowStepSpec("handoff",
                                (context, token) -> context.put("handoff",
                                        "Approved for the next implementation stage"))
                ),
                checkpoints, true
        );

        System.out.println("Software Delivery Agent");
        System.out.println("Repository: " + repository);
        String executionId = "review-" + System.currentTimeMillis();
        WorkflowResult review = workflow.run(executionId,
                Map.of("repository", repository.toString()), new CancellationToken());
        System.out.println("Workflow status: " + review.status());
        System.out.println("Workflow details: " + review.checkpoint().state());
        if (review.status() == WorkflowStatus.WAITING_APPROVAL) {
            System.out.println("Set APPROVE_WORKFLOW=true and rerun to continue past the approval gate.");
        }
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
        long files;
        try (var paths = Files.walk(repository)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(repository.resolve(".git")))
                    .count();
        }
        context.put("fileCount", files);
        context.put("hasPom", Files.exists(repository.resolve("pom.xml")));
        context.put("hasGitMetadata", Files.isDirectory(repository.resolve(".git")));
        context.put("repositoryReady", true);
    }

    private static String runCheck(Path repository, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<Exception> readerFailure = new AtomicReference<>();
        Thread reader = Thread.startVirtualThread(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(output);
            } catch (Exception failure) {
                readerFailure.set(failure);
            }
        });
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(2_000);
            throw new IllegalStateException("Verification timed out: " + String.join(" ", command));
        }
        reader.join(2_000);
        if (readerFailure.get() != null) {
            throw new IllegalStateException("Could not read verification output", readerFailure.get());
        }
        String result = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        if (result.length() > 8_192) {
            result = result.substring(0, 8_192) + "\n[output truncated]";
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Verification failed (" + process.exitValue() + "):\n" + result);
        }
        return result.isBlank() ? "passed" : "passed:\n" + result.trim();
    }
}
