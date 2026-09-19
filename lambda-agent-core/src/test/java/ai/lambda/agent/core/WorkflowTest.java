package ai.lambda.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTest {

    @Test
    void checkpointsEachStepAndResumesAfterFailure() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AtomicInteger secondAttempts = new AtomicInteger();
        Workflow workflow = new Workflow("order", List.of(
                (context, token) -> context.put("prepared", true),
                (context, token) -> {
                    if (secondAttempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary");
                    }
                    context.put("completed", true);
                }
        ), store);

        WorkflowExecutionException failure = assertThrows(WorkflowExecutionException.class,
                () -> workflow.run("run-1", Map.of("input", "value"), new CancellationToken()));
        assertEquals(1, failure.getStep());
        assertEquals(WorkflowStatus.FAILED, store.load("run-1").orElseThrow().status());
        assertEquals(1, store.load("run-1").orElseThrow().nextStep());

        WorkflowResult result = workflow.run("run-1", Map.of(), new CancellationToken());

        assertEquals(WorkflowStatus.COMPLETED, result.status());
        assertEquals(true, result.checkpoint().state().get("prepared"));
        assertEquals(true, result.checkpoint().state().get("completed"));
        assertEquals(2, secondAttempts.get());
    }

    @Test
    void completedExecutionIsIdempotent() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AtomicBoolean ran = new AtomicBoolean();
        Workflow workflow = new Workflow("once", List.of(
                (context, token) -> ran.set(true)
        ), store);

        WorkflowResult first = workflow.run("run-1", Map.of(), new CancellationToken());
        ran.set(false);
        WorkflowResult second = workflow.run("run-1", Map.of(), new CancellationToken());

        assertEquals(WorkflowStatus.COMPLETED, first.status());
        assertEquals(WorkflowStatus.COMPLETED, second.status());
        assertFalse(ran.get());
    }

    @Test
    void cancellationStopsBeforeNextStep() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        CancellationToken token = new CancellationToken();
        Workflow workflow = new Workflow("cancel", List.of(
                (context, ignored) -> token.cancel(),
                (context, ignored) -> fail("second step must not run")
        ), store);

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> workflow.run("run-1", Map.of(), token));
        assertEquals(1, store.load("run-1").orElseThrow().nextStep());
    }
}
