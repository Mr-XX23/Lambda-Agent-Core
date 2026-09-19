package ai.lambda.agent.core;

@FunctionalInterface
public interface TraceExporter {
    void export(TraceEvent event);
}
