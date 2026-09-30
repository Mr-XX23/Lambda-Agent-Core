package ai.lambda.agent.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ParallelWorkflowTest {

    private final InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();

    @Test
    void runsAllBranchesAtTheSameTimeAndSavesTheirState() {
        // Each branch waits for the other to start: this only finishes if they really run in parallel.
        CountDownLatch bothStarted = new CountDownLatch(2);
        WorkflowStep waitForBoth = (context, token) -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(10, TimeUnit.SECONDS), "branches did not run at the same time");
        };
        ParallelWorkflow workflow = new ParallelWorkflow(List.of(
                new WorkflowStepSpec("tests", (context, token) -> {
                    waitForBoth.execute(context, token);
                    context.put("tests", "passed");
                }),
                new WorkflowStepSpec("lint", (context, token) -> {
                    waitForBoth.execute(context, token);
                    context.put("lint", "clean");
                })), checkpoints);

        WorkflowResult result = workflow.run("exec-1", Map.of("branch", "main"), new CancellationToken());

        assertEquals(WorkflowStatus.COMPLETED, result.status());
        assertEquals("exec-1", result.executionId());
        WorkflowCheckpoint stored = checkpoints.load("exec-1").orElseThrow();
        assertEquals(Map.of("branch", "main", "tests", "passed", "lint", "clean"), stored.state());
        assertEquals(2, stored.nextStep());
    }

    @Test
    void aFailingBranchFailsTheRunAndSavesNothing() {
        ParallelWorkflow workflow = new ParallelWorkflow(List.of(
                new WorkflowStepSpec("ok", (context, token) -> context.put("ok", true)),
                new WorkflowStepSpec("bad", (context, token) -> {
                    throw new IllegalStateException("lint failed");
                })), checkpoints);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> workflow.run("exec-1", Map.of(), new CancellationToken()));

        assertEquals("lint failed", error.getCause().getMessage());
        assertTrue(checkpoints.load("exec-1").isEmpty());
    }

    @Test
    void needsAtLeastOneBranch() {
        assertThrows(IllegalArgumentException.class, () -> new ParallelWorkflow(List.of(), checkpoints));
        assertThrows(IllegalArgumentException.class, () -> Workflow.parallel("join", List.of()));
    }

    @Test
    void aFailingParallelBranchCancelsTheOthersAndFailsTheStep() {
        CancellationToken token = new CancellationToken();
        Workflow workflow = new Workflow("checks", List.of(Workflow.parallel("verify", List.of(
                new WorkflowStepSpec("bad", (context, t) -> {
                    throw new IllegalStateException("tests failed");
                }),
                new WorkflowStepSpec("ok", (context, t) -> context.put("ok", true))))), checkpoints);

        WorkflowExecutionException error = assertThrows(WorkflowExecutionException.class,
                () -> workflow.run("exec-1", Map.of(), token));

        assertEquals(0, error.getStep());
        assertEquals("exec-1", error.getExecutionId());
        assertTrue(token.isCancelled(), "the other branches are told to stop");
        assertEquals(WorkflowStatus.FAILED, checkpoints.load("exec-1").orElseThrow().status());
    }

    @Test
    void stepsRetryAccordingToTheirPolicy() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowStepSpec flaky = new WorkflowStepSpec("flaky", (context, token) -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("not yet");
            context.put("done", true);
        }, RetryPolicy.exponential(3, Duration.ofMillis(1)), Duration.ofSeconds(5), null);

        WorkflowResult result = new Workflow("retrying", List.of(flaky), checkpoints, true)
                .run("exec-1", Map.of(), new CancellationToken());

        assertEquals(WorkflowStatus.COMPLETED, result.status());
        assertEquals(3, attempts.get());
    }

    @Test
    void aStepThatRunsTooLongFails() {
        WorkflowStepSpec slow = new WorkflowStepSpec("slow", (context, token) -> Thread.sleep(10_000),
                RetryPolicy.none(), Duration.ofMillis(50), null);

        WorkflowExecutionException error = assertThrows(WorkflowExecutionException.class,
                () -> new Workflow("slow", List.of(slow), checkpoints, true).run("exec-1", Map.of(), new CancellationToken()));

        assertTrue(error.getCause().getMessage().contains("timed out"), error.getCause().getMessage());
        WorkflowCheckpoint stored = checkpoints.load("exec-1").orElseThrow();
        assertEquals(WorkflowStatus.FAILED, stored.status());
        assertTrue(stored.error().contains("'slow' timed out"));
    }

    @Test
    void stepsWhoseConditionIsFalseAreSkipped() {
        WorkflowStepSpec deploy = new WorkflowStepSpec("deploy", (context, token) -> context.put("deployed", true),
                null, Duration.ofSeconds(5), context -> "main".equals(context.get("branch")));

        WorkflowResult skipped = new Workflow("release", List.of(deploy), checkpoints, true)
                .run("feature-run", Map.of("branch", "feature"), new CancellationToken());
        WorkflowResult ran = new Workflow("release", List.of(deploy), checkpoints, true)
                .run("main-run", Map.of("branch", "main"), new CancellationToken());

        assertEquals(WorkflowStatus.COMPLETED, skipped.status());
        assertNull(skipped.checkpoint().state().get("deployed"));
        assertEquals(true, ran.checkpoint().state().get("deployed"));
    }

    @Test
    void anExecutionIdBelongsToOneWorkflow() {
        WorkflowStep step = (context, token) -> {
            throw new IllegalStateException("stop");
        };
        assertThrows(WorkflowExecutionException.class,
                () -> new Workflow("first", List.of(step), checkpoints).run("exec-1", Map.of(), new CancellationToken()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Workflow("second", List.of(step), checkpoints).run("exec-1", Map.of(), new CancellationToken()));
        assertTrue(error.getMessage().contains("first"));
        assertThrows(IllegalArgumentException.class, () -> new Workflow("empty", List.<WorkflowStep>of(), checkpoints));
    }

    @Test
    void stepSpecsValidateTheirSettings() {
        WorkflowStep step = (context, token) -> { };
        assertThrows(IllegalArgumentException.class, () -> new WorkflowStepSpec("s", step, null, Duration.ZERO, null));
        assertThrows(NullPointerException.class, () -> new WorkflowStepSpec(null, step));
        WorkflowStepSpec defaults = new WorkflowStepSpec("s", step, null, Duration.ofSeconds(1), null);
        assertEquals(RetryPolicy.none(), defaults.retryPolicy());
        assertTrue(defaults.condition().test(new WorkflowContext(null)));
    }
}
