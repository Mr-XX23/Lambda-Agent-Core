package ai.lambda.examples.softwareDelivery;

import ai.lambda.agent.core.InMemoryCheckpointStore;
import ai.lambda.agent.core.WorkflowContext;
import ai.lambda.agent.core.Workflow;
import ai.lambda.agent.core.WorkflowStepSpec;
import ai.lambda.agent.core.ApprovalWorkflowStep;
import ai.lambda.agent.core.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SoftwareDeliveryAgentTest {

    @Test
    void inspectsRepositoryWithoutWalkingGitMetadata() throws Exception {
        Path repository = Files.createTempDirectory("delivery-agent-");
        Files.createDirectories(repository.resolve(".git"));
        Files.writeString(repository.resolve("pom.xml"), "<project/>");
        Files.writeString(repository.resolve("README.md"), "# test");

        WorkflowContext context = new WorkflowContext(null);
        SoftwareDeliveryAgent.inspect(repository, context);

        assertEquals(2L, context.get("fileCount"));
        assertEquals(true, context.get("hasPom"));
        assertEquals(true, context.get("hasGitMetadata"));
        assertEquals(true, context.get("repositoryReady"));
    }

    @Test
    void rejectsCommandsOutsideTheVerificationAllowlist() throws Exception {
        Path repository = Files.createTempDirectory("delivery-agent-");

        SecurityException error = assertThrows(SecurityException.class,
                () -> SoftwareDeliveryAgent.runCheck(repository, List.of("rm", "-rf", ".")));

        assertTrue(error.getMessage().contains("not allowlisted"));
    }

    @Test
    void reportsFailedVerificationWithExitStatus() throws Exception {
        Path repository = Files.createTempDirectory("delivery-agent-");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> SoftwareDeliveryAgent.runCheck(repository, List.of("git", "diff", "--check")));

        assertTrue(error.getMessage().contains("Verification failed"));
    }

    @Test
    void pausesAtApprovalGateAfterSuccessfulVerification() throws Exception {
        var store = new InMemoryCheckpointStore();
        var workflow = new Workflow(
                "approval-test",
                List.of(
                        new WorkflowStepSpec("verification",
                                (context, token) -> context.put("verified", true)),
                        new WorkflowStepSpec("approval",
                                new ApprovalWorkflowStep("write", (id, name, context) -> false))
                ),
                store, true
        );

        var result = workflow.run("approval-test", Map.of(), new ai.lambda.agent.core.CancellationToken());

        assertEquals(WorkflowStatus.WAITING_APPROVAL, result.status());
        assertEquals(1, result.checkpoint().nextStep());
    }
}
