package ai.lambda.agent.core;

import java.util.List;

public interface AgentTracer extends AgentEventListener {
    default void accept(TraceEvent event) {}
    List<TraceEvent> snapshot();
}
