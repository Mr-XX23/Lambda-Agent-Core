package ai.lambda.agent.core;

import java.util.List;

public interface AgentTracer extends AgentEventListener {
    List<TraceEvent> snapshot();
}
