package ai.lambda.ai.core;

public record ModelUsage(long inputTokens, long outputTokens, long totalTokens) {
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("Token counts must not be negative");
        }
    }

    public static ModelUsage empty() {
        return new ModelUsage(0, 0, 0);
    }
}
