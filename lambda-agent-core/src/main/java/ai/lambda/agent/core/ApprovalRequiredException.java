package ai.lambda.agent.core;

public final class ApprovalRequiredException extends RuntimeException {
    public ApprovalRequiredException(String message) {
        super(message);
    }
}
