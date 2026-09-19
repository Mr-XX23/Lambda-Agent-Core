package ai.lambda.agent.core;

import java.util.List;
import java.util.Objects;

public final class TraceReplay {
    public List<TraceEvent> replay(List<TraceEvent> events, AgentTracer sink) {
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(sink, "sink must not be null");
        events.forEach(event -> sink.accept(event));
        return sink.snapshot();
    }
}
