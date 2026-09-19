package ai.lambda.agent.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryMetricRecorder implements MetricRecorder {
    private final List<Metric> metrics = new CopyOnWriteArrayList<>();
    public record Metric(String name, long value, boolean timer, Map<String, String> tags) {}
    @Override public void counter(String name, long value, Map<String, String> tags) {
        metrics.add(new Metric(name, value, false, tags));
    }
    @Override public void timer(String name, long value, Map<String, String> tags) {
        metrics.add(new Metric(name, value, true, tags));
    }
    public List<Metric> snapshot() { return List.copyOf(metrics); }
}
