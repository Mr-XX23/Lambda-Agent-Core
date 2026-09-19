package ai.lambda.ai.core;

public enum FinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    UNKNOWN
}
