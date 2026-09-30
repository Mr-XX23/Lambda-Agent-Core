package ai.lambda.agent.core;

import ai.lambda.ai.core.ChatResponse;
import ai.lambda.ai.core.Message;
import ai.lambda.ai.core.ToolCall;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Sends every trace event a tracer records to a {@link TraceExporter}. Add it to an agent as a
 * listener in place of the tracer it wraps:
 *
 * <pre>
 * agent.addListener(new ExportingAgentTracer(new InMemoryAgentTracer(), new OtlpTraceExporter(endpoint)));
 * </pre>
 */
public final class ExportingAgentTracer implements AgentTracer {
    private final AgentTracer delegate;
    private final TraceExporter exporter;
    private int exported;

    public ExportingAgentTracer(AgentTracer delegate, TraceExporter exporter) {
        this.delegate = Objects.requireNonNull(delegate);
        this.exporter = Objects.requireNonNull(exporter);
        this.exported = delegate.snapshot().size(); // events recorded before wrapping are not re-sent
    }

    /** Exports the events the delegate recorded since the last call; returns how many. */
    private synchronized int exportNew() {
        List<TraceEvent> events = delegate.snapshot();
        int sent = 0;
        for (; exported < events.size(); exported++, sent++) exporter.export(events.get(exported));
        return sent;
    }

    @Override public void onRunStart(String runId, String sessionId) { delegate.onRunStart(runId, sessionId); exportNew(); }
    @Override public void onRunEnd(AgentResult result) { delegate.onRunEnd(result); exportNew(); }
    @Override public void onModelResponse(ChatResponse response) { delegate.onModelResponse(response); exportNew(); }
    @Override public void onModelRetry(int attempt, Exception error, Duration delay) { delegate.onModelRetry(attempt, error, delay); exportNew(); }
    @Override public void onIterationStart(int iteration) { delegate.onIterationStart(iteration); exportNew(); }
    @Override public void onAssistantMessage(Message assistantMessage) { delegate.onAssistantMessage(assistantMessage); exportNew(); }
    @Override public void onToolStart(ToolCall call, ToolInvocationContext context) { delegate.onToolStart(call, context); exportNew(); }
    @Override public void onToolEnd(ToolCall call, ToolResult result) { delegate.onToolEnd(call, result); exportNew(); }
    @Override public void onToolError(ToolCall call, Exception error) { delegate.onToolError(call, error); exportNew(); }
    @Override public void onToolAudit(ToolAuditEvent event) { delegate.onToolAudit(event); exportNew(); }
    @Override public void onAssistantDelta(String delta) { delegate.onAssistantDelta(delta); }
    @Override public void onSubagentStart(String subagent, String task, int depth) { delegate.onSubagentStart(subagent, task, depth); exportNew(); }
    @Override public void onSubagentEnd(String subagent, int depth, AgentResult result) { delegate.onSubagentEnd(subagent, depth, result); exportNew(); }
    @Override public void onSubagentError(String subagent, int depth, Exception error) { delegate.onSubagentError(subagent, depth, error); exportNew(); }

    @Override
    public synchronized void accept(TraceEvent event) {
        delegate.accept(event);
        if (exportNew() == 0) exporter.export(event); // a delegate that does not keep events
    }

    @Override
    public List<TraceEvent> snapshot() {
        return delegate.snapshot();
    }
}
