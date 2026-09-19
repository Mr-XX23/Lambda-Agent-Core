package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.ToolCall;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryAgentTracer implements AgentTracer {
    private final List<TraceEvent> events = new CopyOnWriteArrayList<>();
    private final ThreadLocal<RunState> currentRun = new ThreadLocal<>();
    private final ThreadLocal<Long> modelStartedAt = new ThreadLocal<>();
    private final ThreadLocal<Map<String, Long>> toolStartedAt =
            ThreadLocal.withInitial(java.util.HashMap::new);

    @Override
    public void onRunStart(String runId, String sessionId) {
        currentRun.set(new RunState(runId, sessionId, Instant.now()));
        add(TraceEventType.RUN_STARTED, "agent.run", 0, Map.of());
    }

    @Override
    public void onRunEnd(AgentResult result) {
        RunState run = currentRun.get();
        long duration = run == null ? 0 : elapsed(run.startedAt());
        add(TraceEventType.RUN_COMPLETED, "agent.run", duration,
                Map.of("status", "completed", "iterations", result.getIterations()));
        currentRun.remove();
    }

    @Override
    public void onIterationStart(int iteration) {
        modelStartedAt.set(System.nanoTime());
        add(TraceEventType.ITERATION_STARTED, "agent.iteration", 0, Map.of("iteration", iteration));
    }

    @Override
    public void onModelResponse(ChatResponse response) {
        long duration = elapsedNanos(modelStartedAt.get());
        modelStartedAt.remove();
        add(TraceEventType.MODEL_COMPLETED, "model.call", duration,
                Map.of("toolCalls", response.getToolCalls().size(),
                        "inputTokens", response.getUsage().inputTokens(),
                        "outputTokens", response.getUsage().outputTokens(),
                        "finishReason", response.getFinishReason().name()));
    }

    @Override
    public void onModelRetry(int attempt, Exception error, Duration delay) {
        add(TraceEventType.MODEL_RETRY, "model.call", delay.toMillis(),
                Map.of("attempt", attempt, "error", error.getClass().getSimpleName()));
    }

    @Override
    public void onToolStart(ToolCall call, ToolInvocationContext context) {
        toolStartedAt.get().put(call.getId(), System.nanoTime());
        add(TraceEventType.TOOL_STARTED, call.getName(), 0, Map.of("toolCallId", call.getId()));
    }

    @Override
    public void onToolEnd(ToolCall call, ToolResult result) {
        long duration = elapsedNanos(toolStartedAt.get().remove(call.getId()));
        add(TraceEventType.TOOL_COMPLETED, call.getName(), duration,
                Map.of("toolCallId", call.getId(), "resultLength", result.getContent().length()));
    }

    @Override
    public void onToolError(ToolCall call, Exception error) {
        long duration = elapsedNanos(toolStartedAt.get().remove(call.getId()));
        add(TraceEventType.TOOL_FAILED, call.getName(), duration,
                Map.of("toolCallId", call.getId(), "error", error.getClass().getSimpleName()));
    }

    @Override
    public List<TraceEvent> snapshot() {
        return List.copyOf(new ArrayList<>(events));
    }

    @Override
    public void accept(TraceEvent event) {
        events.add(event);
    }

    private void add(TraceEventType type, String name, long duration, Map<String, Object> attributes) {
        RunState run = currentRun.get();
        events.add(new TraceEvent(Instant.now(), run == null ? null : run.runId(),
                run == null ? null : run.sessionId(), type, name, duration, attributes));
    }

    private static long elapsed(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
    }

    private static long elapsedNanos(Long startedAt) {
        return startedAt == null ? 0 : Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private record RunState(String runId, String sessionId, Instant startedAt) {
    }
}
