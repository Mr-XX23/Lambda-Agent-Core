package ai.lambda.agent.core;

import java.util.Map;

public interface MetricRecorder {
    void counter(String name, long value, Map<String, String> tags);
    void timer(String name, long durationMillis, Map<String, String> tags);
}
