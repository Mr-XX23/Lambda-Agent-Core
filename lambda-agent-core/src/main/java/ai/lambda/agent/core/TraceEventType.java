package ai.lambda.agent.core;

public enum TraceEventType {
    RUN_STARTED,
    RUN_COMPLETED,
    ITERATION_STARTED,
    MODEL_COMPLETED,
    MODEL_RETRY,
    TOOL_STARTED,
    TOOL_COMPLETED,
    TOOL_FAILED
}
