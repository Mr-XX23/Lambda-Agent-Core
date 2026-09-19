package ai.lambda.agent.core;

public final class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String executionId, long expected, long actual) {
        super("Checkpoint '" + executionId + "' changed: expected version " + expected
                + ", actual version " + actual);
    }
}
