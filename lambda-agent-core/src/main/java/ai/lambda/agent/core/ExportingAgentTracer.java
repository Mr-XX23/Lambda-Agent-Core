package ai.lambda.agent.core;

import java.util.List;
import java.util.Objects;

public final class ExportingAgentTracer implements AgentTracer {
    private final AgentTracer delegate;
    private final TraceExporter exporter;

    public ExportingAgentTracer(AgentTracer delegate, TraceExporter exporter) {
        this.delegate = Objects.requireNonNull(delegate);
        this.exporter = Objects.requireNonNull(exporter);
    }

    @Override
    public void accept(TraceEvent event) {
        delegate.accept(event);
        exporter.export(event);
    }

    @Override
    public List<TraceEvent> snapshot() {
        return delegate.snapshot();
    }
}
