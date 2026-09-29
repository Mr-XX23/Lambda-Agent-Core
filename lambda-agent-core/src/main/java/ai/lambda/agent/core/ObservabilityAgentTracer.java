package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.ToolCall;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ObservabilityAgentTracer implements AgentTracer {
    private final AgentTracer delegate;
    private final MetricRecorder metrics;
    public ObservabilityAgentTracer(AgentTracer delegate, MetricRecorder metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }
    @Override public void onRunStart(String runId, String sessionId) { delegate.onRunStart(runId, sessionId); metrics.counter("agent.runs", 1, Map.of()); }
    @Override public void onRunEnd(AgentResult result) { delegate.onRunEnd(result); }
    @Override public void onIterationStart(int iteration) { delegate.onIterationStart(iteration); metrics.counter("agent.iterations", 1, Map.of()); }
    @Override public void onModelResponse(ChatResponse response) { delegate.onModelResponse(response); metrics.counter("model.calls", 1, Map.of("finish_reason", response.getFinishReason().name())); }
    @Override public void onModelRetry(int attempt, Exception error, Duration delay) { delegate.onModelRetry(attempt, error, delay); metrics.counter("model.retries", 1, Map.of()); }
    @Override public void onToolStart(ToolCall call, ToolInvocationContext context) { delegate.onToolStart(call, context); metrics.counter("tool.calls", 1, Map.of("tool", call.getName())); }
    @Override public void onToolEnd(ToolCall call, ToolResult result) { delegate.onToolEnd(call, result); }
    @Override public void onToolError(ToolCall call, Exception error) { delegate.onToolError(call, error); metrics.counter("tool.errors", 1, Map.of("tool", call.getName())); }
    @Override public void onAssistantDelta(String delta) { delegate.onAssistantDelta(delta); }
    @Override public void accept(TraceEvent event) { delegate.accept(event); }
    @Override public List<TraceEvent> snapshot() { return delegate.snapshot(); }
}
